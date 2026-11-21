import com.tripperbridge.ArrowClassifier;
import com.tripperbridge.MapsParser;
import com.tripperbridge.TripperProtocol;
import java.util.Arrays;
import java.util.List;

/** Runs on a PC with no phone or bike. Checks packets against known-good values and the parser against sample text. */
public class FrameCheck {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    static boolean crcOk(byte[] p) {
        int c = TripperProtocol.crc16(p, 18);
        return p.length == 20 && p[18] == (byte) (c >> 8) && p[19] == (byte) c;
    }

    static MapsParser.Info parse(String sub, int rb, String... primary) {
        List<String> l = Arrays.asList(primary);
        return MapsParser.parse(l, sub, rb);
    }

    static int parseNow(String sub, int now) {
        return MapsParser.parse(Arrays.asList("x"), sub, 0, now).timeMin;
    }

    public static void main(String[] a) {
        // Known-good packets from WazeTripper's PROTOCOL.md
        check("PING matches spec",
                TripperProtocol.hex(TripperProtocol.ping()).equals("03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 45 D9"));
        check("HANDSHAKE_RESUME matches spec",
                TripperProtocol.hex(TripperProtocol.handshakeResume()).equals("21 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 40 45"));
        check("HANDSHAKE_SHOW_PIN matches spec",
                TripperProtocol.hex(TripperProtocol.handshakeShowPin()).equals("21 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 50 A7"));

        // Every other packet: right length and valid CRC
        byte[][] all = {
            TripperProtocol.pin("123456"), TripperProtocol.setTime(9, 5, true), TripperProtocol.setTime(15, 30, false),
            TripperProtocol.call(), TripperProtocol.compass(0x10, false), TripperProtocol.compass(0x60, true),
            TripperProtocol.navigate(0x14, 200, 0xFF, false, new int[] {0xFF, 0xFF, 0xFF}),
            TripperProtocol.navigate(0x15, 22900, 0xFF, true, TripperProtocol.bottomTimeRemaining(65)),
            TripperProtocol.routeStarted(false), TripperProtocol.recalculating(true)
        };
        boolean allCrc = true;
        for (byte[] p : all) allCrc &= crcOk(p);
        check("all builders give 20 bytes with a valid CRC", allCrc);

        // Layout checks
        byte[] n = TripperProtocol.navigate(0x14, 200, 0xFF, false, new int[] {0xFF, 0xFF, 0xFF});
        check("navigate 200 m: icon/dist/unit", n[2] == 0x14 && n[3] == 0 && (n[4] & 0xFF) == 200 && n[5] == 1);
        check("navigate: marker 0x41 at [10], FF FF at [8-9]", n[10] == 0x41 && n[8] == (byte) 0xFF && n[9] == (byte) 0xFF);
        byte[] k = TripperProtocol.navigate(0x15, 22900, 0xFF, false, new int[] {0xFF, 0xFF, 0xFF});
        check("navigate 22.9 km sent as 229, unit 2", k[3] == 0 && (k[4] & 0xFF) == 229 && k[5] == 2);
        check("intensity table", TripperProtocol.intensity(0) == 0x40 && TripperProtocol.intensity(20) == 0x40
                && TripperProtocol.intensity(21) == 0x30 && TripperProtocol.intensity(45) == 0x30
                && TripperProtocol.intensity(46) == 0x20 && TripperProtocol.intensity(70) == 0x20
                && TripperProtocol.intensity(71) == 0x10 && TripperProtocol.intensity(100) == 0x10
                && TripperProtocol.intensity(101) == 0x00 && TripperProtocol.intensity(250) == 0x00
                && TripperProtocol.intensity(251) == 0x50 && TripperProtocol.intensity(-1) == 0x50);
        byte[] unk = TripperProtocol.navigate(0xFF, -1, 0xFF, false, new int[] {0xFF, 0xFF, 0xFF});
        check("unknown distance: bytes 0, far look", unk[3] == 0 && unk[4] == 0 && unk[6] == 0x50);
        byte[] nn = TripperProtocol.navigate(0x14, 60, 0xFF, true, new int[] {0xFF, 0xFF, 0xFF});
        check("night adds 1 to byte [6]", nn[6] == 0x21);
        byte[] c = TripperProtocol.compass(0x50, true);
        check("compass layout", c[0] == 0x10 && c[1] == 0x11 && c[2] == 0x41 && c[6] == 1 && c[7] == (byte) 0xFF
                && c[11] == (byte) 0xFF && c[12] == (byte) 0xFF && c[13] == (byte) 0xFF && c[14] == 0x50);
        byte[] t12 = TripperProtocol.setTime(9, 5, false);
        byte[] t12pm = TripperProtocol.setTime(15, 30, false);
        check("12h time: 9 AM = 0x49, 3 PM = 0x03", t12[1] == 0x49 && t12pm[1] == 0x03);
        byte[] cl = TripperProtocol.call();
        check("call packet is 40 05", cl[0] == 0x40 && cl[1] == 0x05);
        byte[] pn = TripperProtocol.pin("123456");
        check("PIN packet is 20 + ASCII", pn[0] == 0x20 && pn[1] == '1' && pn[6] == '6');
        int[] bt = TripperProtocol.bottomTimeRemaining(65);
        check("time remaining 65 min = 1h05", bt[0] == 1 && bt[1] == 5 && bt[2] == 0);

        // Parser
        MapsParser.Info i1 = parse("12 min · 3.4 km", 0, "250 m", "Turn left onto Ring Road");
        check("parse: turn left, 250 m, 12 min", i1.icon == 0x14 && i1.distM == 250 && i1.timeMin == 12);
        MapsParser.Info i2 = parse(null, 0, "Turn right onto X · 1.2 km");
        check("parse: turn right, 1.2 km = 1200 m", i2.icon == 0x15 && i2.distM == 1200);
        check("parse: roundabout 2nd exit, left-hand set = 0x0C",
                parse(null, 0, "At the roundabout, take the 2nd exit", "300 m").icon == 0x0C);
        check("parse: roundabout 2nd exit, right-hand set = 0x33",
                parse(null, 1, "At the roundabout, take the 2nd exit", "300 m").icon == 0x33);
        check("parse: roundabout, no exit number = 0x0A", parse(null, 0, "At the roundabout").icon == 0x0A);
        check("parse: U-turn = 0x1A", parse(null, 0, "Make a U-turn").icon == 0x1A);
        check("parse: slight left = 0x18", parse(null, 0, "Slight left onto NH 44").icon == 0x18);
        check("parse: sharp right = 0x17", parse(null, 0, "Sharp right").icon == 0x17);
        check("parse: keep right = 0x27", parse(null, 0, "Keep right at the fork".replace("fork", "junction")).icon == 0x27);
        check("parse: fork left = 0x06", parse(null, 0, "At the fork, keep left").icon == 0x06);
        check("parse: exit left = 0x2E", parse(null, 0, "Take the exit on the left").icon == 0x2E);
        check("parse: merge right = 0x03", parse(null, 0, "Merge right onto NH 48").icon == 0x03);
        check("parse: continue straight = 0x09", parse(null, 0, "Continue straight").icon == 0x09);
        check("parse: head north = 0x09", parse(null, 0, "Head north on MG Road").icon == 0x09);
        check("parse: destination on the right = 0x02", parse(null, 0, "Your destination will be on the right").icon == 0x02);
        check("parse: unknown text gives no icon", parse(null, 0, "Some random text").icon == -1);
        check("parse: '5 min' is not a distance", parse("5 min", 0, "5 min").distM == -1);
        check("parse: 1 hr 5 min = 65", parse("1 hr 5 min · 40 km", 0, "x").timeMin == 65);
        check("parse: 500 ft = 152 m", parse(null, 0, "500 ft", "Turn left").distM == 152);
        check("parse: recalculating flag", parse(null, 0, "Rerouting...").recalculating);

        // Arrow recogniser (templates are real arrows captured from Google Maps)
        String sA = ArrowClassifier.templateArt(0);
        String rA = ArrowClassifier.templateArt(1);
        String lA = ArrowClassifier.templateArt(2);
        check("arrow: straight sample -> 0x09", ArrowClassifier.classify(sA) == 0x09);
        check("arrow: right sample -> 0x15", ArrowClassifier.classify(rA) == 0x15);
        check("arrow: left sample -> 0x14", ArrowClassifier.classify(lA) == 0x14);
        System.out.printf("      overlap straight/right %.2f, straight/left %.2f, right/left %.2f%n",
                ArrowClassifier.similarity(sA, rA), ArrowClassifier.similarity(sA, lA), ArrowClassifier.similarity(rA, lA));
        check("arrow: different arrows do not look alike (all < 0.6)",
                ArrowClassifier.similarity(sA, rA) < 0.6 && ArrowClassifier.similarity(sA, lA) < 0.6
                        && ArrowClassifier.similarity(rA, lA) < 0.6);
        StringBuilder mir = new StringBuilder();
        for (String row : rA.split("\n")) mir.append(new StringBuilder(row).reverse()).append('\n');
        double mirSim = ArrowClassifier.similarity(mir.toString(), lA);
        System.out.printf("      mirror(right) vs left overlap %.2f%n", mirSim);
        check("arrow: left sample is the mirror of the right sample", mirSim > 0.9);
        StringBuilder sh = new StringBuilder();
        for (String row : lA.split("\n")) sh.append(".").append(row, 0, 31).append('\n');
        check("arrow: left arrow shifted 1 px still matches", ArrowClassifier.classify(sh.toString()) == 0x14);

        String mA = ArrowClassifier.templateArt(3), uA = ArrowClassifier.templateArt(4), dA = ArrowClassifier.templateArt(5);
        check("arrow: merge sample -> 0x1B", ArrowClassifier.classify(mA) == 0x1B);
        check("arrow: U-turn sample -> 0x1A", ArrowClassifier.classify(uA) == 0x1A);
        check("arrow: destination sample -> 0x00", ArrowClassifier.classify(dA) == 0x00);
        StringBuilder um = new StringBuilder();
        for (String row : uA.split("\n")) um.append(new StringBuilder(row).reverse()).append('\n');
        check("arrow: mirrored U-turn -> 0x3D", ArrowClassifier.classify(um.toString()) == 0x3D);
        String[] all6 = new String[ArrowClassifier.templateCount()];
        for (int q = 0; q < all6.length; q++) all6[q] = ArrowClassifier.templateArt(q);
        double worst = 0; String pair = "";
        for (int x = 0; x < all6.length; x++) for (int y = x + 1; y < all6.length; y++) {
            double v = ArrowClassifier.similarity(all6[x], all6[y]);
            if (v > worst) { worst = v; pair = x + "/" + y; }
        }
        System.out.printf("      worst overlap between different arrows %.2f (%s)%n", worst, pair);
        check("arrow: no two different arrows overlap 0.6 or more", worst < 0.6);

        String slA = ArrowClassifier.templateArt(6);
        check("arrow: slight left sample -> 0x18", ArrowClassifier.classify(slA) == 0x18);
        StringBuilder sr = new StringBuilder();
        for (String row : slA.split("\n")) sr.append(new StringBuilder(row).reverse()).append('\n');
        check("arrow: mirrored slight left -> slight right 0x19", ArrowClassifier.classify(sr.toString()) == 0x19);
        check("arrow: left turn is not slight left", ArrowClassifier.classify(lA) == 0x14);
        // arrival-clock minutes
        check("eta: 'Arrive 12:07' at 11:50 = 17", parseNow("Arrive 12:07", 11 * 60 + 50) == 17);
        check("eta: '6:57 am ETA' at 06:50 = 7", parseNow("7 x 6:57 am ETA".replace("7 x ", ""), 6 * 60 + 50) == 7);
        check("eta: '11:09 pm' at 23:00 = 9", parseNow("9 km · 11:09 pm ETA", 23 * 60) == 9);
        check("eta: '19:37 ETA' past midnight wrap", parseNow("0 x 00:10 ETA".replace("0 x ", ""), 23 * 60 + 50) == 20);
        check("eta: minutes in text win over the clock", MapsParser.parse(Arrays.asList("x"), "7 min · 3 km · 6:57 am ETA", 0, 400).timeMin == 7);

        String krA = ArrowClassifier.templateArt(7);
        check("arrow: keep right sample -> 0x27", ArrowClassifier.classify(krA) == 0x27);
        StringBuilder kl = new StringBuilder();
        for (String row : krA.split("\n")) kl.append(new StringBuilder(row).reverse()).append('\n');
        check("arrow: mirrored keep right -> keep left 0x28", ArrowClassifier.classify(kl.toString()) == 0x28);
        String realSlightRight = "................................\n................................\n................................\n................................\n............############........\n............############........\n............############........\n..................######........\n.................#######........\n................########........\n...............#####.###........\n..............#####..###........\n.............#####...###........\n............#####....###........\n...........#####.....###........\n..........#####......###........\n.........#####..................\n.........####...................\n........####....................\n........####....................\n.......####.....................\n.......####.....................\n.......###......................\n.......###......................\n.......###......................\n.......###......................\n.......###......................\n.......###......................\n................................\n................................\n................................\n................................\n";
        check("arrow: REAL slight-right picture from the S26 log -> 0x19", ArrowClassifier.classify(realSlightRight) == 0x19);
        java.util.List<String> shapes = new java.util.ArrayList<>();
        for (int q = 0; q < ArrowClassifier.templateCount(); q++) shapes.add(ArrowClassifier.templateArt(q));
        for (int q : new int[] {4, 6, 7}) {
            StringBuilder mm = new StringBuilder();
            for (String row : ArrowClassifier.templateArt(q).split("\n")) mm.append(new StringBuilder(row).reverse()).append('\n');
            shapes.add(mm.toString());
        }
        double worst2 = 0;
        for (int x = 0; x < shapes.size(); x++) for (int y = x + 1; y < shapes.size(); y++)
            worst2 = Math.max(worst2, ArrowClassifier.similarity(shapes.get(x), shapes.get(y)));
        System.out.printf("      worst overlap incl. mirrored shapes %.2f%n", worst2);
        check("arrow: all shapes incl. mirrors stay apart (< 0.7)", worst2 < 0.7);
        check("parse: 'Arriving' = destination 0x00", parse(null, 0, "Arriving", "at Example Co").icon == 0x00);
        StringBuilder blank = new StringBuilder();
        StringBuilder checker = new StringBuilder();
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                blank.append('.');
                checker.append((x + y) % 2 == 0 ? '#' : '.');
            }
            blank.append('\n');
            checker.append('\n');
        }
        check("arrow: blank picture -> no match", ArrowClassifier.classify(blank.toString()) == -1);
        check("arrow: noise -> no match", ArrowClassifier.classify(checker.toString()) == -1);
        check("arrow: junk text -> no match", ArrowClassifier.classify("(arrow picture failed)\n") == -1
                && ArrowClassifier.classify(null) == -1);

        System.out.println(failures == 0 ? "\nALL CHECKS PASSED" : "\n" + failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
