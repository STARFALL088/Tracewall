package tracewall.child.vpn;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;
import android.system.OsConstants;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Answers "which app made this DNS query".
 *
 * A VpnService sees packets, not uids, so the uid has to be looked up from the
 * socket that produced the packet. Two paths, in order of quality:
 *
 *   1. ConnectivityManager.getConnectionOwnerUid (API 29+) - the platform's
 *      own answer, no parsing, no guessing.
 *   2. /proc/net/udp* - a linear scan of the kernel's socket table, matching
 *      the local port. Works on older releases; on modern Android /proc/net
 *      is restricted, so this usually returns nothing and we say so.
 *
 * When neither works the query is logged as "unknown" rather than blamed on
 * the wrong app: a parental control tool that names the wrong child app is
 * worse than one that admits it does not know.
 */
public final class UidAttributor {

    private static final String TAG = "Tracewall";
    public static final int UNKNOWN_UID = -1;

    private static final byte[] DNS_ADDR = {10, 66, 0, 1};

    private final ConnectivityManager cm;
    private boolean procWarningLogged;

    public UidAttributor(Context ctx) {
        this.cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /**
     * @param localIp   source address of the query packet, as the device sees it
     * @param localPort source port of the query
     * @return the owning uid, or {@link #UNKNOWN_UID}
     */
    public int resolve(byte[] localIp, int localPort) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cm != null) {
            try {
                InetSocketAddress local =
                        new InetSocketAddress(InetAddress.getByAddress(localIp), localPort);
                InetSocketAddress remote =
                        new InetSocketAddress(InetAddress.getByAddress(DNS_ADDR), 53);
                int uid = cm.getConnectionOwnerUid(OsConstants.IPPROTO_UDP, local, remote);
                if (uid >= 0) return uid;
            } catch (Exception e) {
                // Address resolution or a missing network - fall through.
                Log.d(TAG, "getConnectionOwnerUid: " + e.getMessage());
            }
        }
        return fromProc(localPort);
    }

    /** Local-port scan of the kernel socket tables. Best effort, often empty. */
    private int fromProc(int localPort) {
        int uid = scan("/proc/net/udp", localPort);
        if (uid != UNKNOWN_UID) return uid;
        uid = scan("/proc/net/udp6", localPort);
        if (uid == UNKNOWN_UID && !procWarningLogged) {
            procWarningLogged = true;
            Log.i(TAG, "no uid for DNS query on port " + localPort
                    + " - /proc/net is restricted on this build");
        }
        return uid;
    }

    private int scan(String path, int localPort) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = r.readLine();                 // header
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 8) continue;
                int colon = f[1].lastIndexOf(':');
                if (colon < 0) continue;
                int port;
                try {
                    port = Integer.parseInt(f[1].substring(colon + 1), 16);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (port != localPort) continue;
                try {
                    return Integer.parseInt(f[7]);
                } catch (NumberFormatException e) {
                    return UNKNOWN_UID;
                }
            }
        } catch (IOException e) {
            Log.d(TAG, "cannot read " + path + ": " + e.getMessage());
        }
        return UNKNOWN_UID;
    }
}
