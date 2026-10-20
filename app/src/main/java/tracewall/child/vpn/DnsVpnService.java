package tracewall.child.vpn;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import tracewall.child.R;
import tracewall.child.block.BlocklistStore;
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
 * No startForeground(): a VpnService already gets a system-owned notification
 * and is exempt from the API 34 foreground-service-type rules. Adding one
 * would mean declaring a specialUse subtype to buy nothing.
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
            Arrays.asList("malware", "gambling", "adult", "ads"));

    private static volatile boolean running = false;
    private static volatile int ruleCount = 0;

    /** True between establish() and teardown, for the UI to trust. */
    public static boolean isRunning() { return running; }

    public static int ruleCount() { return ruleCount; }

    private final BlocklistStore blocklist = new BlocklistStore();
    private UidAttributor uidAttributor;
    private final Object writeLock = new Object();

    private Thread tunThread;
    private ParcelFileDescriptor tun;
    private FileOutputStream tunOut;
    private ExecutorService workers;
    private DnsForwarder forwarder;
    private volatile boolean stop;

    @Override
    public void onCreate() {
        super.onCreate();
        workers = Executors.newFixedThreadPool(4);
        uidAttributor = new UidAttributor(this);
        List<String> resolvers = systemResolvers();
        forwarder = new DnsForwarder(resolvers, DnsForwarder.DEFAULT_TIMEOUT_MS,
                new DnsForwarder.SocketBinder() {
                    @Override public void bind(DatagramSocket s) { protect(s); }
                    @Override public void bind(Socket s) { protect(s); }
                });
        Log.i(TAG, "upstream resolvers: " + forwarder.resolvers());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startTunnel();
        return START_STICKY;
    }

    private synchronized void startTunnel() {
        if (tunThread != null) return;         // already up or starting
        stop = false;
        tunThread = new Thread(this, "tracewall-tun");
        tunThread.start();
    }

    /** Tunnel thread: load rules, establish, then read packets until stopped. */
    @Override
    public void run() {
        try {
            blocklist.load(this, DEFAULT_CATEGORIES, Collections.<String>emptyList());
            ruleCount = blocklist.size();

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
            running = true;
            Log.i(TAG, "tunnel up: " + blocklist.summary());
            loop();
        } catch (Exception e) {
            Log.e(TAG, "tunnel failed", e);
        } finally {
            teardown();
        }
    }

    private void loop() throws IOException {
        FileInputStream in = new FileInputStream(tun.getFileDescriptor());
        byte[] buf = new byte[32767];
        while (!stop) {
            int n = in.read(buf);
            if (n < 0) break;                  // fd closed by teardown
            if (n == 0) continue;

            final IpPacket pkt = IpPacket.parse(buf, 0, n);
            if (pkt == null || !pkt.isUdp() || pkt.dstPort() != IpPacket.PORT_DNS) {
                continue;                      // nothing else is routed here
            }
            try {
                workers.execute(new Runnable() {
                    @Override public void run() { handle(pkt); }
                });
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

        String host = q.qName();
        // Attribution first: a blocked query is never forwarded, so there is
        // no later point at which the socket still exists to look up.
        int uid = uidAttributor == null
                ? UidAttributor.UNKNOWN_UID
                : uidAttributor.resolve(pkt.srcIpBytes(), pkt.srcPort());
        boolean blocked = blocklist.blocks(host);
        byte[] answer;
        String resolver = "local";

        if (blocked) {
            answer = DnsMessage.buildAResponse(q, new byte[] {0, 0, 0, 0});
        } else {
            try {
                int wireId = forwarder.nextId();          // our id, not the client's
                DnsForwarder.Reply r = forwarder.forward(query, wireId);
                answer = DnsMessage.normalizeResponse(r.bytes, q.id(), false);
                resolver = r.resolver;
            } catch (DnsForwarder.ForwardException e) {
                Log.w(TAG, "forward " + host + ": " + e.getMessage());
                answer = DnsMessage.buildServFail(q);     // fail fast, do not retry-loop
                resolver = "servfail";
            }
        }
        if (answer == null) return;

        byte[] reply = pkt.buildResponse(pkt.dstIpBytes(), pkt.srcIpBytes(),
                answer, pkt.identification());
        if (reply == null) return;
        write(reply);

        QueryLog.get().add(new QueryLog.Entry(System.currentTimeMillis(), host,
                q.qType(), blocked, pkt.srcPort(), uid,
                (System.nanoTime() - t0) / 1_000_000L, resolver));
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
        stop = true;
        running = false;
        synchronized (writeLock) {
            if (tunOut != null) {
                try { tunOut.close(); } catch (IOException ignored) { }
                tunOut = null;
            }
        }
        if (tun != null) {
            try { tun.close(); } catch (IOException ignored) { }
            tun = null;
        }
        tunThread = null;
        ruleCount = 0;
        Log.i(TAG, "tunnel down");
    }

    @Override
    public void onRevoke() {
        Log.i(TAG, "permission revoked - another VPN took over, or the user said no");
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stop = true;
        running = false;
        if (workers != null) workers.shutdownNow();
        if (forwarder != null) forwarder.close();
        if (tun != null) {
            try { tun.close(); } catch (IOException ignored) { }
            tun = null;
        }
        super.onDestroy();
    }

    /**
     * The network's own resolvers, read before establish() so we are not
     * looking at our own tunnel. Loopback, link-local and our fake resolver
     * are filtered out; if that leaves nothing, DnsForwarder falls back to
     * public resolvers and says so in the log.
     */
    private List<String> systemResolvers() {
        List<String> out = new ArrayList<>();
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm == null ? null : cm.getActiveNetwork();
            LinkProperties lp = net == null ? null : cm.getLinkProperties(net);
            if (lp != null) {
                for (InetAddress a : lp.getDnsServers()) {
                    if (a == null || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String host = a.getHostAddress();
                    if (DNS_ADDRESS.equals(host)) continue;
                    out.add(host);
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot read system resolvers: " + e.getMessage());
        }
        return out;
    }
}
