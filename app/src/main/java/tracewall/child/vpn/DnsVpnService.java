package tracewall.child.vpn;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import tracewall.blocklist.PolicyEngine;
import tracewall.child.R;
import tracewall.child.ui.Notifications;
import tracewall.child.sync.FirebaseSync;
import tracewall.child.block.BlocklistStore;
import tracewall.child.log.HistoryWriter;
import tracewall.child.log.QueryLog;
import tracewall.net.DnsForwarder;
import tracewall.net.DnsMessage;
import tracewall.net.IpPacket;

/**
 * The whole product: a VPN interface that only carries DNS.
 *
 * Routing is deliberately minimal. We add one address (10.66.0.2), advertise
 * one resolver (10.66.0.1) and route exactly that single address into the
 * tunnel. Everything that is not a DNS query to our fake resolver stays on the
 * real network, so a bug here cannot break the phone - the worst case is that
 * names stop resolving, which is visible and reversible.
 *
 * Two things about this file are load-bearing and easy to get wrong:
 *
 *   1. Upstream sockets are passed through {@link #protect}. Skip that and the
 *      forwarder's own queries go back into the tunnel, which recurses until
 *      file descriptors run out. Every DNS VPN bug looks like this one.
 *   2. Replies are written with correct IPv4 and UDP checksums. Android does
 *      not fix them up for a TUN write, and a bad checksum means the client
 *      silently drops our answer - the app looks broken for no visible reason.
 *
 * The system VPN indicator does not replace the service's foreground lifetime.
 * A consented VPN qualifies for Android 14's systemExempted foreground type.
 */
public class DnsVpnService extends VpnService implements Runnable {

    private static final String TAG = "Tracewall";

    public static final String ACTION_START = "tracewall.intent.START";
    public static final String ACTION_STOP = "tracewall.intent.STOP";

    /** Interface address the device sees inside the tunnel. */
    private static final String DEVICE_ADDRESS = "10.66.0.2";
    /** The resolver we advertise; it exists only inside our own TUN. */
    private static final String DNS_ADDRESS = "10.66.0.1";

    private static final Set<String> DEFAULT_CATEGORIES = new HashSet<>(
        Arrays.asList("malware", "gambling", "adult", "ads")
    );

    private static final AtomicBoolean running = new AtomicBoolean(false);

    /** True between establish() and teardown, for the UI to trust. */
    public static boolean isRunning() {
        return running.get();
    }

    private final BlocklistStore blocklist = BlocklistStore.get();
    private UidAttributor uidAttributor;
    private final Object writeLock = new Object();

    private Thread tunThread;
    private ParcelFileDescriptor tun;
    private FileOutputStream tunOut;
    private ExecutorService workers;
    private volatile DnsForwarder forwarder;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean stop;
    private BroadcastReceiver packageReceiver;
    private final AtomicBoolean offReported = new AtomicBoolean();

    /** Android side of the pure-JVM forwarder's binder contract. */
    private final DnsForwarder.SocketBinder binder =
        new DnsForwarder.SocketBinder() {
            @Override
            public void bind(DatagramSocket s) {
                protect(s);
            }

            @Override
            public void bind(Socket s) {
                protect(s);
            }
        };

    @Override
    public void onCreate() {
        super.onCreate();
        workers = Executors.newFixedThreadPool(4);
        uidAttributor = new UidAttributor(this);
        HistoryWriter.get().start(this);
        packageReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())
                        || intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) || intent.getData() == null) return;
                String packageName = intent.getData().getSchemeSpecificPart();
                if (packageName == null || packageName.isEmpty()) return;
                long installedAt = System.currentTimeMillis();
                PendingResult pending = goAsync();
                try {
                    workers.execute(() -> {
                        try {
                            String label = packageName;
                            try { label = String.valueOf(getPackageManager().getApplicationLabel(
                                getPackageManager().getApplicationInfo(packageName, 0))); }
                            catch (android.content.pm.PackageManager.NameNotFoundException ignored) { /* Removed immediately after install. */ }
                            if (label.length() > 200) label = label.substring(0, 200);
                            tracewall.child.ui.Notifications.showNewApp(DnsVpnService.this, label, packageName);
                            FirebaseSync.get().reportNewApp(label, packageName, installedAt);
                        } finally { pending.finish(); }
                    });
                } catch (RejectedExecutionException e) { pending.finish(); }
            }
        };
        IntentFilter installs = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        installs.addDataScheme("package");
        // PACKAGE_ADDED is a protected system broadcast; exported permits privileged installers.
        androidx.core.content.ContextCompat.registerReceiver(this, packageReceiver, installs,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED);

        forwarder = new DnsForwarder(
            systemResolvers(),
            DnsForwarder.DEFAULT_TIMEOUT_MS,
            binder
        );
        Log.i(TAG, "upstream resolvers: " + forwarder.resolvers());

        // Wi-Fi to mobile data is the classic way a DNS VPN silently dies: the
        // old resolvers become unroutable and every lookup times out. Following
        // the default network keeps upstream alive without restarting the tunnel.
        ConnectivityManager cm = (ConnectivityManager) getSystemService(
            Context.CONNECTIVITY_SERVICE
        );
        if (cm != null) {
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    refreshUpstream();
                }

                @Override
                public void onLost(Network network) {
                    refreshUpstream();
                }
            };
            try {
                cm.registerDefaultNetworkCallback(networkCallback);
            } catch (RuntimeException e) {
                Log.w(
                    TAG,
                    "cannot watch the default network: " + e.getMessage()
                );
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            reportOff("user_toggle");
            BlocklistStore.setEnabled(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            android.app.Notification notification = Notifications.protectionActive(this);
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifications.ID_PROTECTION_ACTIVE, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
            } else {
                startForeground(Notifications.ID_PROTECTION_ACTIVE, notification);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "cannot enter foreground; VPN was not started", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        startTunnel();
        return START_STICKY;
    }

    private synchronized void startTunnel() {
        if (tunThread != null) return; // already up or starting
        stop = false;
        tunThread = new Thread(this, "tracewall-tun");
        tunThread.start();
    }

    /** Tunnel thread: load rules, establish, then read packets until stopped. */
    @Override
    public void run() {
        try {
            blocklist.load(this, DEFAULT_CATEGORIES);

            Builder b = new Builder();
            b.setSession(getString(R.string.app_name));
            b.setMtu(IpPacket.TUN_MTU);
            b.setBlocking(true);
            b.addAddress(DEVICE_ADDRESS, 24);
            b.addDnsServer(DNS_ADDRESS);
            b.addRoute(DNS_ADDRESS, 32);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                b.setMetered(false);
            }

            tun = b.establish();
            if (tun == null) {
                Log.w(TAG, "establish() returned null - permission revoked?");
                return;
            }
            tunOut = new FileOutputStream(tun.getFileDescriptor());
            running.set(true);
            BlocklistStore.setEnabled(this, true);
            Log.i(TAG, "tunnel up: " + blocklist.summary());
            loop();
        } catch (Exception e) {
            Log.e(TAG, "tunnel failed", e);
        } finally {
            teardown();
            // A failed/closed TUN must not leave an idle foreground service behind.
            stopSelf();
        }
    }

    private void loop() throws IOException {
        FileInputStream in = new FileInputStream(tun.getFileDescriptor());
        byte[] buf = new byte[32767];
        while (!stop) {
            int n = in.read(buf);
            if (n < 0) break; // fd closed by teardown
            if (n == 0) continue;

            final IpPacket pkt = IpPacket.parse(buf, 0, n);
            if (
                pkt == null ||
                !pkt.isUdp() ||
                pkt.dstPort() != IpPacket.PORT_DNS
            ) {
                continue; // nothing else is routed here
            }
            try {
                workers.execute(
                    new Runnable() {
                        @Override
                        public void run() {
                            handle(pkt);
                        }
                    }
                );
            } catch (RejectedExecutionException ignored) {
                // shutting down; drop the packet
            }
        }
    }

    /** Worker thread: decide, answer, write back. */
    private void handle(IpPacket pkt) {
        long t0 = System.nanoTime();
        byte[] query = pkt.payload();
        DnsMessage q = DnsMessage.parse(query);
        if (q == null || !q.isSimpleQuery()) return;

        // SVCB (type 64) and HTTPS (type 65) are the encrypted-DNS discovery
        // records defined in RFC 9460. Android queries _dns.resolver.arpa SVCB
        // at boot to auto-discover DoH/DoT servers. Returning NXDOMAIN prevents
        // the system from finding a server that would bypass this filter.
        if (
            q.qType() == DnsMessage.TYPE_SVCB ||
            q.qType() == DnsMessage.TYPE_HTTPS
        ) {
            byte[] nxd = DnsMessage.buildNxDomain(q);
            if (nxd != null) {
                byte[] reply = pkt.buildResponse(
                    pkt.dstIpBytes(),
                    pkt.srcIpBytes(),
                    nxd,
                    pkt.identification()
                );
                if (reply != null) write(reply);
            }
            return;
        }

        String host = q.qName();
        // Attribution first: a blocked query is never forwarded, so there is
        // no later point at which the socket still exists to look up.
        int uid =
            uidAttributor == null
                ? UidAttributor.UNKNOWN_UID
                : uidAttributor.resolve(pkt.srcIpBytes(), pkt.srcPort());
        PolicyEngine.Decision decision = blocklist.decide(host, uid);
        boolean blocked = decision.blocked();
        byte[] answer;
        String resolver = "local";

        if (blocked) {
            answer = q.qType() == DnsMessage.TYPE_A
                ? DnsMessage.buildAResponse(q, new byte[] { 0, 0, 0, 0 })
                : DnsMessage.buildEmptyResponse(q);
        } else {
            try {
                DnsForwarder current = forwarder;
                DnsForwarder.Reply r;
                try {
                    r = current.forward(query, current.nextId());
                } catch (DnsForwarder.ForwardException e) {
                    // Only a retired pool warrants one retry after handover.
                    DnsForwarder replacement = forwarder;
                    if (!current.isClosed() || replacement == current) throw e;
                    r = replacement.forward(query, replacement.nextId());
                }
                answer = DnsMessage.normalizeResponse(r.bytes, q.id(), false);
                resolver = r.resolver;
            } catch (DnsForwarder.ForwardException e) {
                Log.w(TAG, "forward " + host + ": " + e.getMessage());
                answer = DnsMessage.buildServFail(q); // fail fast, do not retry-loop
                resolver = "servfail";
            }
        }
        if (answer == null) return;

        byte[] reply = pkt.buildResponse(
            pkt.dstIpBytes(),
            pkt.srcIpBytes(),
            answer,
            pkt.identification()
        );
        if (reply == null) return;
        write(reply);

        QueryLog.Entry entry = new QueryLog.Entry(
            System.currentTimeMillis(),
            host,
            q.qType(),
            blocked,
            pkt.srcPort(),
            uid,
            decision.reason,
            (System.nanoTime() - t0) / 1_000_000L,
            resolver
        );
        QueryLog.get().add(entry);
        HistoryWriter.get().offer(entry.toRecord());
    }

    private void write(byte[] packet) {
        synchronized (writeLock) {
            if (tunOut == null) return;
            try {
                tunOut.write(packet);
            } catch (IOException e) {
                Log.w(TAG, "tun write failed: " + e.getMessage());
            }
        }
    }

    private void teardown() {
        reportOff("unexpected_stop");
        stop = true;
        running.set(false);
        synchronized (writeLock) {
            if (tunOut != null) {
                try {
                    tunOut.close();
                } catch (IOException ignored) {}
                tunOut = null;
            }
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException ignored) {}
            tun = null;
        }
        tunThread = null;
        Log.i(TAG, "tunnel down");
    }

    private void reportOff(String reason) {
        if ((running.get() || BlocklistStore.isEnabled(this))
                && offReported.compareAndSet(false, true)) {
            FirebaseSync.get().reportProtectionOff(reason);
        }
    }

    @Override
    public void onRevoke() {
        reportOff("vpn_revoked");
        Log.i(
            TAG,
            "permission revoked - another VPN took over, or the user said no"
        );
        stopSelf();
    }

    @Override
    public void onDestroy() {
        reportOff("service_destroyed");
        stop = true;
        running.set(false);
        if (packageReceiver != null) {
            unregisterReceiver(packageReceiver);
            packageReceiver = null;
        }
        if (networkCallback != null) {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(
                    Context.CONNECTIVITY_SERVICE
                );
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
                // already unregistered, or the process is going away
            }
            networkCallback = null;
        }
        if (workers != null) workers.shutdownNow();
        if (forwarder != null) forwarder.close();
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException ignored) {}
            tun = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    /**
     * The physical network's own resolvers.
     *
     * Reads every non-VPN network rather than getActiveNetwork(): once the
     * tunnel is up, the active network IS the tunnel, and its only resolver is
     * our own fake address - forwarding to that would be a loop.
     *
     * Loopback, link-local and the fake resolver are filtered out. If nothing
     * survives, DnsForwarder falls back to public resolvers and logs which ones.
     */
    private List<String> systemResolvers() {
        List<String> out = new ArrayList<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(
                Context.CONNECTIVITY_SERVICE
            );
            if (cm == null) return out;
            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (
                    caps == null ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) continue;
                LinkProperties lp = cm.getLinkProperties(net);
                if (lp == null) continue;
                for (InetAddress a : lp.getDnsServers()) {
                    if (
                        a == null ||
                        a.isLoopbackAddress() ||
                        a.isLinkLocalAddress()
                    ) continue;
                    String host = a.getHostAddress();
                    if (
                        DNS_ADDRESS.equals(host) || out.contains(host)
                    ) continue;
                    out.add(host);
                }
                if (!out.isEmpty()) break; // first connected physical network wins
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot read system resolvers: " + e.getMessage());
        }
        return out;
    }

    /** Rebuild the forwarder after a network switch. Cheap; the old pool closes. */
    private synchronized void refreshUpstream() {
        List<String> resolvers = systemResolvers();
        if (resolvers.isEmpty()) return;
        DnsForwarder old = forwarder;
        forwarder = new DnsForwarder(
            resolvers,
            DnsForwarder.DEFAULT_TIMEOUT_MS,
            binder
        );
        if (old != null) old.close();
        Log.i(TAG, "upstream resolvers refreshed: " + resolvers);
    }
}
