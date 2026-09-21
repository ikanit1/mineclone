import com.mineclone.net.NetChannel;
import com.mineclone.net.NetTransport;
import com.mineclone.net.PhotonTransport;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end check of the Photon leg, without starting the game.
 *
 * Photon ships no Java SDK, so the game talks to Photon Cloud over the
 * WebSocket + JSON entry point its own web client uses. That path can only be
 * proven against a real App ID, and an App ID belongs to an account - so it
 * cannot live in the repo and cannot be covered by run-tests.ps1.
 *
 *   java -cp "out;libs/*" tools/PhotonSmoke.java &lt;app-id&gt; [region] [room]
 *
 * Or set MINECLONE_PHOTON_APPID and pass nothing. The tool opens two clients:
 * the first creates a room, the second joins it, they exchange one event, and
 * both leave. Prints every step; exits non-zero on the first failure.
 *
 * Messages are Latin on purpose: the Windows console is not UTF-8.
 */
public class PhotonSmoke {

    private static final long STEP_TIMEOUT_MS = 20_000;

    public static void main(String[] args) throws Exception {
        String appId = args.length > 0 ? args[0] : System.getenv("MINECLONE_PHOTON_APPID");
        String region = args.length > 1 ? args[1] : "";
        String room = args.length > 2 ? args[2] : "smoke-" + (System.currentTimeMillis() % 100000);
        if (appId == null || appId.isBlank()) {
            System.out.println("usage: java -cp \"out;libs/*\" tools/PhotonSmoke.java <app-id> [region] [room]");
            System.out.println("       (or set MINECLONE_PHOTON_APPID)");
            System.out.println("App ID comes from dashboard.photonengine.com, a Realtime application.");
            System.exit(2);
            return;
        }
        System.out.println("photon smoke: room=" + room
                + " region=" + (region.isEmpty() ? "auto" : region));

        Client host = new Client("host");
        Client guest = new Client("guest");
        host.transport = new PhotonTransport(appId, region, true, host);
        guest.transport = new PhotonTransport(appId, region, true, guest);

        int failures = 0;
        try {
            host.transport.connect(room, true, "SmokeHost");
            failures += step("host joined the room", () -> host.joined.get(), host, guest);
            if (failures == 0)
                System.out.println("     actor=" + host.transport.myActor()
                        + " created=" + host.created.get() + " " + host.transport.describe());

            guest.transport.connect(room, false, "SmokeGuest");
            failures += step("guest joined the same room", () -> guest.joined.get(), host, guest);
            failures += step("host saw the guest arrive", () -> host.peers.get() > 0, host, guest);
            failures += step("guest sees the host as room master",
                    () -> guest.transport.masterActor() == host.transport.myActor(), host, guest);

            byte[] hello = "mineclone-photon-smoke".getBytes(StandardCharsets.UTF_8);
            host.transport.send(hello, true, NetChannel.ALL);
            failures += step("event travelled host -> guest",
                    () -> new String(guest.lastPayload.get() == null ? new byte[0]
                            : guest.lastPayload.get(), StandardCharsets.UTF_8).equals(
                                    "mineclone-photon-smoke"),
                    host, guest);

            guest.transport.send("pong".getBytes(StandardCharsets.UTF_8), true,
                    host.transport.myActor());
            failures += step("event travelled guest -> host (addressed)",
                    () -> host.lastPayload.get() != null
                            && new String(host.lastPayload.get(), StandardCharsets.UTF_8)
                                    .equals("pong"),
                    host, guest);

            guest.transport.disconnect();
            failures += step("host saw the guest leave", () -> host.left.get() > 0, host, guest);
        } finally {
            guest.transport.disconnect();
            host.transport.disconnect();
            pump(host, guest, 200);
        }

        if (failures == 0) {
            System.out.println("photon smoke: all checks passed");
            System.exit(0);
        }
        System.out.println("photon smoke: " + failures + " check(s) failed");
        System.exit(1);
    }


    /**
     * Transliterate Cyrillic to Latin.
     *
     * The transport speaks to the player, so its texts are Russian; the
     * Windows console is not UTF-8 and turns them into noise. Everything the
     * tool prints goes through here. The alphabet is written with unicode
     * escapes on purpose: a single-file launch reads the source in the
     * platform encoding, and literal Cyrillic here would not survive it.
     */
    private static final String CYRILLIC =
            "абвгдеёжзи"
            + "йклмнопрст"
            + "уфхцчшщъыь"
            + "эюя";

    private static final String[] LATIN = {
            "a", "b", "v", "g", "d", "e", "e", "zh", "z", "i",
            "y", "k", "l", "m", "n", "o", "p", "r", "s", "t",
            "u", "f", "h", "c", "ch", "sh", "sch", "", "y", "",
            "e", "yu", "ya" };

    static String ascii(String s) {
        if (s == null)
            return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                out.append(c);
                continue;
            }
            int idx = CYRILLIC.indexOf(Character.toLowerCase(c));
            if (idx < 0) {
                // Guillemets and the ellipsis are the only other non-ASCII
                // characters the texts use.
                out.append(c == '«' || c == '»' ? '"' : '.');
                continue;
            }
            String piece = LATIN[idx];
            if (Character.isUpperCase(c) && !piece.isEmpty())
                piece = Character.toUpperCase(piece.charAt(0)) + piece.substring(1);
            out.append(piece);
        }
        return out.toString();
    }

    private interface Condition {
        boolean ready();
    }

    /** Pump both clients until the condition holds or the step times out. */
    private static int step(String what, Condition c, Client a, Client b)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + STEP_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            a.transport.poll();
            b.transport.poll();
            if (a.failed.get() != null || b.failed.get() != null)
                break;
            if (c.ready()) {
                System.out.println("  ok - " + what);
                return 0;
            }
            Thread.sleep(25);
        }
        String why = a.failed.get() != null ? a.failed.get()
                : b.failed.get() != null ? b.failed.get() : "timed out";
        System.out.println("  FAIL - " + what + " (" + why + ")");
        return 1;
    }

    private static void pump(Client a, Client b, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            a.transport.poll();
            b.transport.poll();
            Thread.sleep(25);
        }
    }

    /** One Photon client and everything it has seen so far. */
    private static final class Client implements NetTransport.Listener {
        final String tag;
        PhotonTransport transport;
        final AtomicBoolean joined = new AtomicBoolean();
        final AtomicBoolean created = new AtomicBoolean();
        final AtomicInteger peers = new AtomicInteger();
        final AtomicInteger left = new AtomicInteger();
        final AtomicReference<byte[]> lastPayload = new AtomicReference<>();
        final AtomicReference<String> failed = new AtomicReference<>();

        Client(String tag) {
            this.tag = tag;
        }

        @Override
        public void onState(NetTransport.State state, String detail) {
            System.out.println("     [" + tag + "] " + state + ": " + ascii(detail));
            if (state == NetTransport.State.FAILED)
                failed.set(ascii(detail));
        }

        @Override
        public void onJoined(int myActor, boolean wasCreated) {
            created.set(wasCreated);
            joined.set(true);
        }

        @Override
        public void onActorJoin(int actor, String name) {
            peers.incrementAndGet();
            System.out.println("     [" + tag + "] actor " + actor + " joined (" + name + ")");
        }

        @Override
        public void onActorLeave(int actor) {
            left.incrementAndGet();
            System.out.println("     [" + tag + "] actor " + actor + " left");
        }

        @Override
        public void onPayload(int from, byte[] data) {
            lastPayload.set(data);
        }

        @Override
        public void onRoomList(List<NetTransport.RoomInfo> rooms) {
            System.out.println("     [" + tag + "] rooms: " + rooms.size());
        }
    }
}
