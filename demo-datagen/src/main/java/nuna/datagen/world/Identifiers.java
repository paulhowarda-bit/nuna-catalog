package nuna.datagen.world;

/**
 * Security identifiers that cannot be real: CUSIPs whose issuer number starts 990 to 999 (a range a secondary source
 * calls reserved for internal use; CUSIP Global Services has not confirmed it, docs/design/brokerage-world.md), with
 * proper check digits, and the ISIN built on each.
 */
public final class Identifiers {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** The CUSIP of the n-th synthetic issuer (0 to 9999), issue number "10" (an equity). */
    public static String cusip(int n) {
        String issuer = "99" + (n / 1000) + String.format("%03d", n % 1000);
        String base = issuer + "10";
        return base + cusipCheck(base);
    }

    /** CUSIP's check digit: the "modulus 10 double add double" over the first eight characters. */
    static int cusipCheck(String eight) {
        int sum = 0;
        for (int i = 0; i < 8; i++) {
            char c = eight.charAt(i);
            int v = Character.isDigit(c) ? c - '0' : ALPHABET.indexOf(c);
            if (i % 2 == 1) {
                v *= 2;
            }
            sum += v / 10 + v % 10;
        }
        return (10 - sum % 10) % 10;
    }

    /** The ISIN of a US CUSIP: "US", the CUSIP, and the Luhn check digit over the letters' values. */
    public static String isin(String cusip) {
        String body = "US" + cusip;
        StringBuilder digits = new StringBuilder();
        for (char c : body.toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c - '0') : String.valueOf(ALPHABET.indexOf(c)));
        }
        int sum = 0;
        boolean dbl = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int v = digits.charAt(i) - '0';
            if (dbl) {
                v *= 2;
                if (v > 9) {
                    v -= 9;
                }
            }
            sum += v;
            dbl = !dbl;
        }
        return body + (10 - sum % 10) % 10;
    }

    private Identifiers() {}
}
