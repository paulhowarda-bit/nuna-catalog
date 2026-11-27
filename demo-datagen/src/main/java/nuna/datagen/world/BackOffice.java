package nuna.datagen.world;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The firm's back office (item 152): the parties behind the brokerage accounts and their KYC records, the payments
 * system's view of every money movement the ledger books, ACATS transfers, and compliance: sanctions screening against
 * a synthetic list in OFAC's SDN layout, AML monitoring with its alerts and cases, trade surveillance and complaints.
 *
 * <p>It draws from a source of chance of its own, so the ledger's world draws exactly what it drew before; it only
 * observes the world's bookings, which call it with the ledger's ids, amounts and dates. Every identifier is synthetic
 * (docs/design/brokerage-world.md): tax ids in SSN area 666, which SSA never issues, and EINs with prefix 00, which
 * the IRS never issues; phones 555-0100 to 0199; email at example.com; bank routing numbers with first two digits 50,
 * a range the ABA does not use; and sanctions entries that are invented, never OFAC's.
 *
 * <p>The accounts are the world's: their onboarding records are written once, at the world's first day, each dated as
 * its account opened. After that each business day brings changes of address, contact and employment, suitability
 * reviews, new trusted contacts and complaints. Each record of a party, a suitability profile, a trusted contact, a
 * payment, an alert, a case or a complaint is a new version of it ({@link LakeSchemas#VERSIONED}).
 */
final class BackOffice {

    /** The synthetic sanctions list's version, published once at the world's first day. */
    static final String LIST_VERSION = "DEMO-SDN-2026-11";
    /** AML structuring (31 USC 5324): three or more cash deposits from $8,000 to under $10,000 in five business days. */
    static final BigDecimal STRUCTURING_LOW = new BigDecimal("8000"), CTR_THRESHOLD = new BigDecimal("10000");
    static final int STRUCTURING_DEPOSITS = 3, STRUCTURING_DAYS = 5;
    /** A wire in or out of $100,000 or more raises an alert, which an analyst reviews the next business day. */
    static final BigDecimal LARGE_WIRE = new BigDecimal("100000");

    private static final List<String> FIRST = List.of("James", "Mary", "Robert", "Patricia", "John", "Jennifer",
        "Michael", "Linda", "David", "Elizabeth", "William", "Barbara", "Richard", "Susan", "Joseph", "Jessica",
        "Thomas", "Sarah", "Charles", "Karen", "Daniel", "Lisa", "Matthew", "Nancy", "Anthony", "Betty", "Mark",
        "Sandra", "Steven", "Ashley", "Andrew", "Emily", "Kevin", "Michelle", "Brian", "Amanda", "Priya", "Wei",
        "Carlos", "Fatima", "Hiroshi", "Olga", "Kwame", "Lucia", "Arjun", "Mei", "Diego", "Aisha", "Tomas", "Ingrid");
    private static final List<String> LAST = List.of("Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia",
        "Miller", "Davis", "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez", "Wilson", "Anderson", "Thomas",
        "Taylor", "Moore", "Jackson", "Martin", "Lee", "Perez", "Thompson", "White", "Harris", "Sanchez", "Clark",
        "Ramirez", "Lewis", "Robinson", "Walker", "Young", "Allen", "King", "Wright", "Scott", "Torres", "Nguyen",
        "Hill", "Flores", "Green", "Adams", "Nelson", "Baker", "Hall", "Rivera", "Campbell", "Mitchell", "Patel",
        "Kim", "Okafor", "Lindqvist", "Novak", "Haddad", "Sato", "Kowalski", "Mensah", "Fischer", "Rossi", "Dubois");
    private static final List<String> STREETS = List.of("Maple Avenue", "Oak Street", "Cedar Lane", "Elm Drive",
        "Pine Road", "Willow Court", "Birch Way", "Lakeview Terrace", "Hillcrest Road", "Sunset Boulevard",
        "Park Place", "River Road", "Meadow Lane", "Highland Avenue", "Church Street", "Mill Road", "Spring Street",
        "Forest Drive", "Ridge Road", "Harbor View");
    private record City(String city, String state, String zip, String area) {}
    private static final List<City> CITIES = List.of(new City("New York", "NY", "10024", "212"),
        new City("Brooklyn", "NY", "11215", "718"), new City("Chicago", "IL", "60614", "312"),
        new City("Evanston", "IL", "60201", "847"), new City("Boston", "MA", "02116", "617"),
        new City("San Francisco", "CA", "94110", "415"), new City("Oakland", "CA", "94610", "510"),
        new City("Seattle", "WA", "98103", "206"), new City("Denver", "CO", "80206", "303"),
        new City("Austin", "TX", "78704", "512"), new City("Atlanta", "GA", "30306", "404"),
        new City("Miami", "FL", "33133", "305"), new City("Phoenix", "AZ", "85016", "602"),
        new City("Philadelphia", "PA", "19103", "215"), new City("Minneapolis", "MN", "55408", "612"),
        new City("Portland", "OR", "97214", "503"), new City("Charlotte", "NC", "28203", "704"),
        new City("Columbus", "OH", "43215", "614"), new City("Nashville", "TN", "37203", "615"),
        new City("Salt Lake City", "UT", "84105", "801"));
    private static final List<String> EMPLOYERS = List.of("Harborline Logistics", "Bluestem Health Partners",
        "Cobalt Ridge Software", "Meridian Civil Works", "Tidewater Foods", "Granite Peak Insurance",
        "Lantern Street Media", "Northfield School District", "Copperleaf Energy", "Summit Valley Hospital",
        "Ironwood Manufacturing", "Silverline Transit", "Brightwater Analytics", "Oakmont Legal Group",
        "Crescent Retail Group");
    private static final List<String> OCCUPATIONS = List.of("software engineer", "nurse", "teacher", "accountant",
        "physician", "attorney", "project manager", "electrician", "sales manager", "pharmacist", "architect",
        "data analyst", "marketing director", "civil engineer", "small business owner");
    private static final List<String> ENTITY_WORDS = List.of("Harbor", "Ridge", "Summit", "Cedar", "Granite",
        "Willow", "Beacon", "Lantern", "Copper", "Meadow", "Falcon", "Juniper", "Orchard", "Keystone", "Tamarack");
    private static final List<String> COUNTERPARTIES = List.of("Northgate Title Services LLC",
        "Riverbend Escrow Company", "Alder Street Properties LP", "Coastal Auction House Inc.",
        "Prairie Mutual Benefit Plan", "Westfork Realty Trust", "Bellmont Settlement Agents LLC",
        "Clearbrook Family Office LLC");
    private static final List<String> RELATIONSHIPS = List.of("spouse", "sibling", "child", "parent", "friend",
        "attorney", "accountant");

    /** One entry of the synthetic sanctions list, in the columns of OFAC's SDN.CSV; invented, never OFAC's. */
    record Sdn(long entNum, String name, String type, String program, String title, String callSign, String vessType,
               String tonnage, String grt, String vessFlag, String vessOwner, String remarks) {}

    private static final String SYNTHETIC = "Synthetic demo entry; not OFAC data.";
    static final List<Sdn> SANCTIONS = List.of(
        new Sdn(9101, "VORTELL ARGENT HOLDINGS FZE", null, "DEMO-FIN", null, null, null, null, null, null, null,
            "a.k.a. VORTELL ARGENT TRADING; " + SYNTHETIC),
        new Sdn(9102, "KAZMIRA SHIPPING COMPANY", null, "DEMO-PROLIF", null, null, null, null, null, null, null,
            SYNTHETIC),
        new Sdn(9103, "ZELBARRO, Ondrej Viktor", "individual", "DEMO-NARCO", "Financier", null, null, null, null, null,
            null, "DOB 04 Mar 1968; " + SYNTHETIC),
        new Sdn(9104, "QUENTARIS LOGISTIK GMBH", null, "DEMO-PROLIF", null, null, null, null, null, null, null,
            SYNTHETIC),
        new Sdn(9105, "MERIDAX STAR", "vessel", "DEMO-PROLIF", null, "9XDM7", "Bulk Carrier", "41200", "23800",
            "Demo Flag", "KAZMIRA SHIPPING COMPANY", "IMO 9900001; " + SYNTHETIC),
        new Sdn(9106, "ABRELLON, Yusra Calder", "individual", "DEMO-CYBER", null, null, null, null, null, null, null,
            "DOB 19 Nov 1985; " + SYNTHETIC),
        new Sdn(9107, "TORVANE DIGITAL SERVICES LTD", null, "DEMO-CYBER", null, null, null, null, null, null, null,
            "Website torvane.example.com; " + SYNTHETIC),
        new Sdn(9108, "OSTRIVA CAPITAL MANAGEMENT", null, "DEMO-FIN", null, null, null, null, null, null, null,
            SYNTHETIC),
        new Sdn(9109, "PELLANOR, Marek", "individual", "DEMO-FIN", "Director", null, null, null, null, null, null,
            "DOB 01 Jan 1971; " + SYNTHETIC),
        new Sdn(9110, "GRAVENTIS TRADE LINKS", null, "DEMO-NARCO", null, null, null, null, null, null, null,
            SYNTHETIC),
        new Sdn(9111, "SOLVAREN BREEZE", "vessel", "DEMO-FIN", null, "9XSB2", "Crude Oil Tanker", "108000", "61500",
            "Demo Flag", "OSTRIVA CAPITAL MANAGEMENT", "IMO 9900002; " + SYNTHETIC),
        new Sdn(9112, "DRAVIK, Ilsa Moren", "individual", "DEMO-NARCO", null, null, null, null, null, null, null,
            "DOB 22 Jul 1979; " + SYNTHETIC));
    /** The entry the seeded wire's originator matches. */
    static final Sdn SEEDED_MATCH = SANCTIONS.getFirst();

    private final Emitter emitter;
    private final MarketCalendar calendar;
    private final Rng rng;

    private final class Party {
        final long id;
        int version = 1;
        String type, legalName, first, last, taxId, taxIdType, address, city, state, zip, email, phone,
            employment, employer, occupation;
        LocalDate birth;
        boolean controlPerson, exposed;

        Party(long id) {
            this.id = id;
        }
    }

    private record Owner(long id, String name, LocalDate birth, String taxId, String address, City city,
                         BigDecimal percent, boolean control) {}

    private final class Account {
        final World.Account world;
        final String registration;
        final List<Party> parties = new ArrayList<>();
        final Map<Party, String> roles = new LinkedHashMap<>();
        final List<Owner> owners = new ArrayList<>();
        final String routing, bankAccount;
        int suitabilityVersion = 1;
        String objective, risk, horizon, liquidity, experience, income, netWorth, liquidNetWorth;
        long contactId;
        int contactVersion;
        String contactName, contactRelationship, contactPhone, contactEmail;
        boolean closed;

        Account(World.Account world, String registration, String routing, String bankAccount) {
            this.world = world;
            this.registration = registration;
            this.routing = routing;
            this.bankAccount = bankAccount;
        }

        Party primary() {
            return parties.getFirst();
        }
    }

    private final class Payment {
        final String id;
        final Account account;
        final String method, direction, counterparty;
        final BigDecimal amount;
        final LocalDate expected;
        int version;

        Payment(String id, Account account, String method, String direction, BigDecimal amount, LocalDate expected,
                String counterparty) {
            this.id = id;
            this.account = account;
            this.method = method;
            this.direction = direction;
            this.amount = amount;
            this.expected = expected;
            this.counterparty = counterparty;
        }
    }

    private record Deposit(LocalDate day, BigDecimal amount, String payment) {}
    private record Fills(long buy, long sell, String symbol) {}

    private final Map<Long, Account> accounts = new LinkedHashMap<>();
    private final List<Party> parties = new ArrayList<>();
    private final Map<Long, Payment> byInitiation = new HashMap<>();
    private final Map<Long, List<Deposit>> cashDeposits = new HashMap<>();
    private final Map<Long, LocalDate> structuringAlerted = new HashMap<>();
    /** Each trade date's buys and sells, per account and security, for the next morning's surveillance. */
    private final Map<LocalDate, Map<List<Long>, long[]>> fills = new TreeMap<>();
    private final Map<Long, String> symbols = new HashMap<>();
    private final Map<LocalDate, List<Runnable>> due = new TreeMap<>();
    private final Account structurer, sanctioned;
    private long nextParty = 90_000_001L, nextRole = 1, nextCip = 1, nextOwner = 1, nextContact = 1, nextScreen = 1,
        nextAlert = 1, nextCase = 1, nextSurveillance = 1, nextComplaint = 1, nextReturn = 1, nextFiling = 1;
    private LocalDate day;
    private Instant now;

    BackOffice(Emitter emitter, MarketCalendar calendar, Rng rng, List<World.Account> worldAccounts) {
        this.emitter = emitter;
        this.calendar = calendar;
        this.rng = rng;
        for (World.Account w : worldAccounts) {
            Account a = new Account(w, registration(w), routingNumber(), digits(10));
            accounts.put(w.id(), a);
            onboardParties(a);
            suitability(a, w.persona());
            if (!a.registration.equals("entity") && !a.registration.equals("trust") && rng.chance(0.4)) {
                trustedContact(a);
            }
        }
        List<Account> eligible = accounts.values().stream()
            .filter(a -> a.world.persona().equals("buy_and_hold") || a.world.persona().equals("active_trader"))
            .filter(a -> a.registration.equals("individual") || a.registration.equals("joint")).toList();
        structurer = eligible.get(rng.below(eligible.size()));
        Account other;
        do {
            other = eligible.get(rng.below(eligible.size()));
        } while (other == structurer);
        sanctioned = other;
    }

    // ---------------------------------------------------------------- drawing the parties

    private String registration(World.Account w) {
        double u = rng.uniform();
        if (w.margin()) {
            return u < 0.70 ? "individual" : u < 0.88 ? "joint" : u < 0.92 ? "trust" : "entity";
        }
        return u < 0.55 ? "individual" : u < 0.70 ? "joint" : u < 0.90 ? "ira" : u < 0.94 ? "trust" : "entity";
    }

    private void onboardParties(Account a) {
        switch (a.registration) {
            case "joint" -> {
                Party first = person();
                Party second = person();
                second.last = first.last;
                second.legalName = second.first + " " + second.last;
                second.address = first.address;
                second.city = first.city;
                second.state = first.state;
                second.zip = first.zip;
                role(a, first, "primary_owner");
                role(a, second, "joint_owner");
            }
            case "trust" -> {
                Party trustee = person();
                Party trust = organisation("trust", trustee.last + " Family Trust", trustee);
                role(a, trust, "account_holder");
                role(a, trustee, "trustee");
                if (rng.chance(0.4)) {
                    Party second = person();
                    second.last = trustee.last;
                    second.legalName = second.first + " " + second.last;
                    role(a, second, "trustee");
                }
            }
            case "entity" -> {
                Party trader = person();
                String name = ENTITY_WORDS.get(rng.below(ENTITY_WORDS.size())) + " "
                    + ENTITY_WORDS.get(rng.below(ENTITY_WORDS.size())) + " " + rng.pick(List.of("LLC", "LP", "Inc."));
                Party entity = organisation("entity", name, trader);
                role(a, entity, "account_holder");
                role(a, trader, "authorized_trader");
                int n = 1 + rng.below(3);
                BigDecimal[] shares = switch (n) {
                    case 1 -> new BigDecimal[] {new BigDecimal("100.00")};
                    case 2 -> rng.chance(0.5) ? new BigDecimal[] {new BigDecimal("50.00"), new BigDecimal("50.00")}
                        : new BigDecimal[] {new BigDecimal("60.00"), new BigDecimal("40.00")};
                    default -> new BigDecimal[] {new BigDecimal("40.00"), new BigDecimal("35.00"), new BigDecimal("25.00")};
                };
                for (int i = 0; i < n; i++) {
                    City city = CITIES.get(rng.below(CITIES.size()));
                    String owner = FIRST.get(rng.below(FIRST.size())) + " " + LAST.get(rng.below(LAST.size()));
                    // The owner who controls the entity is a beneficial owner by control as well (31 CFR 1010.230).
                    a.owners.add(new Owner(nextOwner++, owner, birth(), ssn(), street(), city, shares[i], i == 0));
                }
            }
            default -> role(a, person(), "primary_owner");
        }
    }

    private void role(Account a, Party p, String role) {
        a.parties.add(p);
        a.roles.put(p, role);
    }

    private Party person() {
        Party p = new Party(nextParty++);
        p.type = "individual";
        p.first = FIRST.get(rng.below(FIRST.size()));
        p.last = LAST.get(rng.below(LAST.size()));
        p.legalName = p.first + " " + p.last;
        p.birth = birth();
        p.taxId = ssn();
        p.taxIdType = "ssn";
        City c = CITIES.get(rng.below(CITIES.size()));
        p.address = street();
        p.city = c.city();
        p.state = c.state();
        p.zip = c.zip();
        p.email = email(p.first, p.last);
        p.phone = c.area() + "-555-01" + String.format("%02d", rng.below(100));
        employment(p);
        p.controlPerson = rng.chance(0.02);
        p.exposed = rng.chance(0.005);
        parties.add(p);
        return p;
    }

    private Party organisation(String type, String name, Party contact) {
        Party p = new Party(nextParty++);
        p.type = type;
        p.legalName = name;
        p.taxId = "00-" + digits(7);
        p.taxIdType = "ein";
        p.address = street();
        p.city = contact.city;
        p.state = contact.state;
        p.zip = contact.zip;
        p.email = "accounts@" + name.toLowerCase().replaceAll("[^a-z]+", "-").replaceAll("-+$", "") + ".example.com";
        p.phone = contact.phone;
        parties.add(p);
        return p;
    }

    private void employment(Party p) {
        double u = rng.uniform();
        p.employment = u < 0.62 ? "employed" : u < 0.74 ? "self_employed" : u < 0.94 ? "retired" : u < 0.97 ? "student"
            : "unemployed";
        boolean works = p.employment.equals("employed") || p.employment.equals("self_employed");
        p.employer = p.employment.equals("employed") ? EMPLOYERS.get(rng.below(EMPLOYERS.size())) : null;
        p.occupation = works ? OCCUPATIONS.get(rng.below(OCCUPATIONS.size())) : null;
    }

    private void suitability(Account a, String persona) {
        String[] objective = switch (persona) {
            case "dormant" -> new String[] {"capital_preservation", "income"};
            case "buy_and_hold" -> new String[] {"growth", "income"};
            case "active_trader" -> new String[] {"growth", "speculation"};
            default -> new String[] {"speculation", "speculation"};
        };
        a.objective = objective[rng.below(2)];
        a.risk = switch (a.objective) {
            case "capital_preservation" -> "low";
            case "income" -> rng.chance(0.7) ? "moderate" : "low";
            case "growth" -> rng.chance(0.7) ? "moderate" : "high";
            default -> "high";
        };
        a.horizon = rng.pick(List.of("short", "medium", "long", "long"));
        a.liquidity = rng.pick(List.of("low", "low", "medium", "high"));
        a.experience = persona.equals("dormant") ? rng.pick(List.of("none", "limited"))
            : rng.pick(List.of("limited", "good", "extensive"));
        int band = rng.below(5);
        a.income = List.of("under_50k", "50k_100k", "100k_250k", "250k_500k", "over_500k").get(band);
        a.netWorth = List.of("under_100k", "100k_500k", "500k_1m", "1m_5m", "over_5m").get(Math.min(4, band + rng.below(2)));
        a.liquidNetWorth = List.of("under_100k", "100k_500k", "500k_1m", "1m_5m", "over_5m").get(Math.max(0, band - rng.below(2)));
    }

    private void trustedContact(Account a) {
        a.contactId = nextContact++;
        a.contactVersion = 1;
        String first = FIRST.get(rng.below(FIRST.size()));
        String last = rng.chance(0.5) ? a.primary().last : LAST.get(rng.below(LAST.size()));
        a.contactName = first + " " + last;
        a.contactRelationship = RELATIONSHIPS.get(rng.below(RELATIONSHIPS.size()));
        a.contactPhone = CITIES.get(rng.below(CITIES.size())).area() + "-555-01" + String.format("%02d", rng.below(100));
        a.contactEmail = email(first, last);
    }

    private LocalDate birth() {
        return LocalDate.of(1940 + rng.below(59), 1 + rng.below(12), 1 + rng.below(28));
    }

    /** A Social Security number in area 666, which SSA never issues. */
    private String ssn() {
        return "666-" + String.format("%02d", 1 + rng.below(99)) + "-" + String.format("%04d", 1 + rng.below(9999));
    }

    private String street() {
        return (1 + rng.below(9899)) + " " + STREETS.get(rng.below(STREETS.size()));
    }

    private String email(String first, String last) {
        return (first + "." + last).toLowerCase() + (rng.below(90) + 10) + "@example.com";
    }

    private String digits(int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) {
            s.append(rng.below(10));
        }
        return s.toString();
    }

    /** An ABA routing number whose first two digits are 50, which the ABA does not use, with its check digit. */
    private String routingNumber() {
        String eight = "50" + digits(6);
        int[] w = {3, 7, 1, 3, 7, 1, 3, 7};
        int sum = 0;
        for (int i = 0; i < 8; i++) {
            sum += (eight.charAt(i) - '0') * w[i];
        }
        return eight + (10 - sum % 10) % 10;
    }

    // ---------------------------------------------------------------- what the world asks

    String registration(long account) {
        return accounts.get(account).registration;
    }

    /** The brokerage account whose holder makes the seeded structuring deposits. */
    long structurer() {
        return structurer.world.id();
    }

    /** The brokerage account the seeded sanctioned wire is sent to. */
    long sanctioned() {
        return sanctioned.world.id();
    }

    /** The business days, counted from the world's first (0), of the seeded structuring deposits. */
    static final List<Integer> STRUCTURING_DAYS_PLAYED = List.of(1, 2, 3);
    /** The business day, counted from the world's first (0), of the seeded sanctioned wire. */
    static final int SANCTIONS_DAY_PLAYED = 2;

    /** A seeded structuring deposit's amount: from $9,000 to $9,900, under the currency transaction report's $10,000. */
    BigDecimal structuringAmount() {
        return new BigDecimal(9000 + 50 * rng.below(19)).setScale(2);
    }

    BigDecimal sanctionedWireAmount() {
        return new BigDecimal(48_000 + 500 * rng.below(40)).setScale(2);
    }

    private void at(Instant at, LocalDate today) {
        now = at;
        day = today;
    }

    private void emit(Feed feed, Map<String, Object> row) {
        emitter.emit(now, feed, row);
    }

    // ---------------------------------------------------------------- onboarding, once

    /** The list, and every account's onboarding records, each dated as its account opened. */
    void onboard(Instant at, LocalDate today) {
        at(at, today);
        for (Sdn s : SANCTIONS) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("list_version", LIST_VERSION);
            row.put("ent_num", s.entNum());
            row.put("sdn_name", s.name());
            row.put("sdn_type", s.type());
            row.put("program", s.program());
            row.put("title", s.title());
            row.put("call_sign", s.callSign());
            row.put("vess_type", s.vessType());
            row.put("tonnage", s.tonnage());
            row.put("grt", s.grt());
            row.put("vess_flag", s.vessFlag());
            row.put("vess_owner", s.vessOwner());
            row.put("remarks", s.remarks());
            row.put("published_on", today);
            emit(Feed.SANCTIONS_LIST, row);
        }
        Set<Party> written = new HashSet<>();
        for (Account a : accounts.values()) {
            LocalDate opened = a.world.opened();
            for (Party p : a.parties) {
                if (written.add(p)) {
                    party(p, "onboarding", opened);
                    cip(p, a, opened);
                    screen("onboarding", "party", String.valueOf(p.id), p.legalName, a, null, 0.01);
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("role_id", nextRole++);
                row.put("brokerage_account", a.world.id());
                row.put("party_id", p.id);
                row.put("role", a.roles.get(p));
                row.put("effective_date", opened);
                row.put("event_time", now);
                emit(Feed.ACCOUNT_ROLES, row);
            }
            suitabilityRow(a, "onboarding", opened);
            for (Owner o : a.owners) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("owner_id", o.id());
                row.put("brokerage_account", a.world.id());
                row.put("entity_party_id", a.primary().id);
                row.put("full_name", o.name());
                row.put("birth_date", o.birth());
                row.put("tax_id", o.taxId());
                row.put("address_line", o.address());
                row.put("city", o.city().city());
                row.put("state", o.city().state());
                row.put("postal_code", o.city().zip());
                row.put("ownership_percent", o.percent());
                row.put("control_person", o.control());
                row.put("certified_on", opened);
                row.put("event_time", now);
                emit(Feed.BENEFICIAL_OWNERS, row);
                screen("onboarding", "beneficial_owner", String.valueOf(o.id()), o.name(), a, null, 0.01);
            }
            if (a.contactId > 0) {
                contactRow(a, "onboarding", opened);
            }
        }
    }

    private void party(Party p, String reason, LocalDate effective) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("party_id", p.id);
        row.put("version", p.version);
        row.put("party_type", p.type);
        row.put("legal_name", p.legalName);
        row.put("first_name", p.first);
        row.put("last_name", p.last);
        row.put("birth_date", p.birth);
        row.put("tax_id", p.taxId);
        row.put("tax_id_type", p.taxIdType);
        row.put("address_line", p.address);
        row.put("city", p.city);
        row.put("state", p.state);
        row.put("postal_code", p.zip);
        row.put("country", "US");
        row.put("email", p.email);
        row.put("phone", p.phone);
        row.put("employment_status", p.employment);
        row.put("employer", p.employer);
        row.put("occupation", p.occupation);
        row.put("control_person", p.type.equals("individual") ? p.controlPerson : null);
        row.put("politically_exposed", p.type.equals("individual") ? p.exposed : null);
        row.put("change_reason", reason);
        row.put("effective_date", effective);
        row.put("event_time", now);
        emit(Feed.PARTIES, row);
    }

    /** The Customer Identification Program's verification (31 CFR 1023.220): documents, or non-documentary means. */
    private void cip(Party p, Account a, LocalDate opened) {
        boolean individual = p.type.equals("individual");
        boolean documentary = rng.chance(0.7);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("cip_id", nextCip++);
        row.put("party_id", p.id);
        row.put("brokerage_account", a.world.id());
        row.put("method", documentary ? "documentary" : "non_documentary");
        row.put("evidence", documentary
            ? (individual ? rng.pick(List.of("drivers_license", "drivers_license", "passport", "state_id"))
               : p.type.equals("trust") ? "trust_agreement" : "articles_of_organization")
            : (individual ? "consumer_report" : "business_registry"));
        row.put("result", rng.chance(0.98) ? "verified" : "verified_after_review");
        row.put("checked_on", opened);
        row.put("event_time", now);
        emit(Feed.CIP_CHECKS, row);
    }

    private void suitabilityRow(Account a, String reason, LocalDate effective) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("brokerage_account", a.world.id());
        row.put("version", a.suitabilityVersion);
        row.put("investment_objective", a.objective);
        row.put("risk_tolerance", a.risk);
        row.put("time_horizon", a.horizon);
        row.put("liquidity_needs", a.liquidity);
        row.put("investment_experience", a.experience);
        row.put("annual_income_band", a.income);
        row.put("net_worth_band", a.netWorth);
        row.put("liquid_net_worth_band", a.liquidNetWorth);
        row.put("change_reason", reason);
        row.put("effective_date", effective);
        row.put("event_time", now);
        emit(Feed.SUITABILITY_PROFILES, row);
    }

    private void contactRow(Account a, String reason, LocalDate effective) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("contact_id", a.contactId);
        row.put("version", a.contactVersion);
        row.put("brokerage_account", a.world.id());
        row.put("full_name", a.contactName);
        row.put("relationship", a.contactRelationship);
        row.put("phone", a.contactPhone);
        row.put("email", a.contactEmail);
        row.put("change_reason", reason);
        row.put("effective_date", effective);
        row.put("event_time", now);
        emit(Feed.TRUSTED_CONTACTS, row);
    }

    /**
     * Screens a name against the list. Organic screens never match, but a few come back as potential matches, which the
     * screening analyst clears at once as false positives.
     */
    private void screen(String trigger, String subjectType, String subjectId, String name, Account a, String payment,
                        double potential) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("screen_id", nextScreen++);
        row.put("list_version", LIST_VERSION);
        row.put("screen_trigger", trigger);
        row.put("subject_type", subjectType);
        row.put("subject_id", subjectId);
        row.put("subject_name", name);
        row.put("brokerage_account", a.world.id());
        row.put("payment_id", payment);
        if (rng.chance(potential)) {
            row.put("result", "potential_match");
            row.put("matched_ent_num", SANCTIONS.get(rng.below(SANCTIONS.size())).entNum());
            row.put("match_score", new BigDecimal(70 + rng.below(20)).setScale(2));
            row.put("disposition", "cleared");
        } else {
            row.put("result", "no_match");
            row.put("matched_ent_num", null);
            row.put("match_score", null);
            row.put("disposition", null);
        }
        row.put("business_date", day);
        row.put("screened_at", now);
        emit(Feed.SANCTIONS_SCREENS, row);
    }

    // ---------------------------------------------------------------- money movement, as the ledger books it

    /**
     * A payment the ledger has just booked the start of: a cash or wire deposit received (templates 30, 70), an ACH
     * deposit or withdrawal or a wire out initiated (31, 33, 71). A wire is screened, its counterparty mostly the account's
     * holder; one of $100,000 or more raises an alert.
     */
    void payment(Instant at, LocalDate today, long account, String id, long initiation, int template, String method,
                 String direction, String status, BigDecimal amount, LocalDate expected) {
        at(at, today);
        Account a = accounts.get(account);
        String counterparty = null;
        if (method.equals("wire")) {
            counterparty = rng.chance(direction.equals("deposit") ? 0.8 : 0.85) ? a.primary().legalName
                : COUNTERPARTIES.get(rng.below(COUNTERPARTIES.size()));
        }
        Payment p = new Payment(id, a, method, direction, amount, expected, counterparty);
        byInitiation.put(initiation, p);
        paymentRow(p, status, initiation, template, null);
        if (method.equals("wire")) {
            boolean own = counterparty.equals(a.primary().legalName);
            screen("wire", "wire_counterparty", id, counterparty, a, id, own ? 0.0 : 0.04);
            if (amount.compareTo(LARGE_WIRE) >= 0) {
                String alert = alert("large_wire", a, "open", amount,
                    "A wire " + (direction.equals("deposit") ? "in" : "out") + " of " + amount.toPlainString()
                        + " (" + id + "), at or above the review threshold of " + LARGE_WIRE.toPlainString(), null, 1);
                later(1, () -> alert(alert, "large_wire", a, "closed_no_action", amount,
                    "Reviewed: the wire matches the customer's profile and source of funds", null, 2));
            }
        }
        if (method.equals("cash")) {
            cashDeposits.computeIfAbsent(account, k -> new ArrayList<>()).add(new Deposit(today, amount, id));
        }
    }

    /** A later status of a payment the ledger booked the start of (templates 32, 34, 35, 72, 73). */
    void paymentStatus(Instant at, LocalDate today, long initiation, long ft, int template, String status) {
        at(at, today);
        paymentRow(byInitiation.get(initiation), status, ft, template, null);
    }

    /** An ACH deposit returned before or after it settled (templates 74, 75), and its return. */
    void achReturn(Instant at, LocalDate today, long initiation, long ft, int template, String code) {
        at(at, today);
        Payment p = byInitiation.get(initiation);
        paymentRow(p, "returned", ft, template, null);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("return_id", "RET-" + (44_000_000 + nextReturn++));
        row.put("payment_id", p.id);
        row.put("brokerage_account", p.account.world.id());
        row.put("return_code", code);
        row.put("return_reason", switch (code) {
            case "R01" -> "Insufficient funds";
            case "R02" -> "Account closed";
            case "R03" -> "No account, unable to locate account";
            case "R08" -> "Payment stopped";
            case "R10" -> "Customer advises not authorized";
            case "R16" -> "Account frozen";
            default -> "Returned";
        });
        row.put("amount", p.amount);
        row.put("after_settlement", template == 75);
        row.put("financial_transaction_id", ft);
        row.put("business_date", day);
        row.put("event_time", now);
        emit(Feed.ACH_RETURNS, row);
    }

    /** A cash journal between two brokerage accounts (templates 76 and 77), as one payment. */
    void journal(Instant at, LocalDate today, long from, long to, String journal, long ft, BigDecimal amount) {
        at(at, today);
        Payment p = new Payment(journal, accounts.get(from), "journal", "transfer", amount, null, null);
        paymentRow(p, "settled", ft, 76, to);
    }

    private void paymentRow(Payment p, String status, Long ft, Integer template, Long contra) {
        p.version++;
        boolean bank = p.method.equals("ach") || p.method.equals("wire");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("payment_id", p.id);
        row.put("version", p.version);
        row.put("brokerage_account", p.account.world.id());
        row.put("method", p.method);
        row.put("direction", p.direction);
        row.put("status", status);
        row.put("amount", p.amount);
        row.put("financial_transaction_id", ft);
        row.put("template_id", template);
        row.put("expected_settlement_date", p.expected);
        row.put("bank_routing_number", bank ? p.account.routing : null);
        row.put("bank_account_number", bank ? p.account.bankAccount : null);
        row.put("counterparty_name", p.counterparty);
        row.put("contra_brokerage_account", contra);
        row.put("business_date", day);
        row.put("event_time", now);
        emit(Feed.PAYMENTS, row);
    }

    /** An ACATS transfer settled today (FINRA 11870), requested three business days before. */
    void acats(Instant at, LocalDate today, long account, String control, String direction, String type, String contra,
               BigDecimal cash, int positions, BigDecimal shares, BigDecimal fee) {
        at(at, today);
        LocalDate requested = today;
        for (int i = 0; i < 3; i++) {
            requested = calendar.previous(requested);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("acats_control_number", control);
        row.put("brokerage_account", account);
        row.put("direction", direction);
        row.put("transfer_type", type);
        row.put("contra_firm_number", contra);
        row.put("status", "settled");
        row.put("requested_on", requested);
        row.put("settlement_date", today);
        row.put("cash_amount", cash);
        row.put("positions", positions);
        row.put("shares", shares);
        row.put("fee_amount", fee);
        row.put("event_time", now);
        emit(Feed.ACATS_TRANSFERS, row);
        if (direction.equals("deliver") && type.equals("full")) {
            accounts.get(account).closed = true;
        }
    }

    /**
     * The seeded sanctions match: an incoming wire whose originator is on the list. It is blocked (31 CFR 501.603):
     * never credited, so the ledger books nothing; the screen confirms the match, an alert opens, and the next morning a
     * case reports the blocked property within the ten business days the rule allows.
     */
    void blockedWire(Instant at, LocalDate today, long account, String id, BigDecimal amount) {
        at(at, today);
        Account a = accounts.get(account);
        Payment p = new Payment(id, a, "wire", "deposit", amount, null, SEEDED_MATCH.name());
        paymentRow(p, "blocked", null, null, null);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("screen_id", nextScreen++);
        row.put("list_version", LIST_VERSION);
        row.put("screen_trigger", "wire");
        row.put("subject_type", "wire_counterparty");
        row.put("subject_id", id);
        row.put("subject_name", SEEDED_MATCH.name());
        row.put("brokerage_account", account);
        row.put("payment_id", id);
        row.put("result", "confirmed_match");
        row.put("matched_ent_num", SEEDED_MATCH.entNum());
        row.put("match_score", new BigDecimal("100.00"));
        row.put("disposition", "blocked");
        row.put("business_date", day);
        row.put("screened_at", now);
        emit(Feed.SANCTIONS_SCREENS, row);
        String detail = "Incoming wire " + id + " of " + amount.toPlainString() + " from " + SEEDED_MATCH.name()
            + ", list entry " + SEEDED_MATCH.entNum() + ": blocked";
        String alert = alert("sanctions_match", a, "open", amount, detail, null, 1);
        LocalDate blocked = today;
        later(1, () -> {
            String c = openCase("sanctions_block", a, alert, "blocked_property_report", businessDays(blocked, 10));
            alert(alert, "sanctions_match", a, "escalated", amount, detail, c, 2);
            later(1, () -> fileCase(c));
        });
    }

    // ---------------------------------------------------------------- compliance

    private record Case(String id, String type, Account account, String alert, String filing, LocalDate filingDue,
                        LocalDate opened) {}

    private final Map<String, Case> cases = new HashMap<>();

    private String alert(String scenario, Account a, String status, BigDecimal amount, String detail, String caseId,
                         int version) {
        return alert(null, scenario, a, status, amount, detail, caseId, version);
    }

    private String alert(String id, String scenario, Account a, String status, BigDecimal amount, String detail,
                         String caseId, int version) {
        String alert = id != null ? id : "AML-" + (40_000_000 + nextAlert++);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("alert_id", alert);
        row.put("version", version);
        row.put("scenario", scenario);
        row.put("brokerage_account", a.world.id());
        row.put("party_id", a.primary().id);
        row.put("status", status);
        row.put("amount", amount);
        row.put("detail", detail);
        row.put("case_id", caseId);
        row.put("business_date", day);
        row.put("event_time", now);
        emit(Feed.AML_ALERTS, row);
        return alert;
    }

    private String openCase(String type, Account a, String alert, String filing, LocalDate filingDue) {
        String id = "CASE-" + (41_000_000 + nextCase++);
        Case c = new Case(id, type, a, alert, filing, filingDue, day);
        cases.put(id, c);
        caseRow(c, 1, "open", null, null);
        return id;
    }

    private void fileCase(String id) {
        Case c = cases.get(id);
        boolean sar = c.filing().equals("sar");
        caseRow(c, 2, sar ? "sar_filed" : "report_filed", day, (sar ? "SAR-DEMO-" : "OFAC-DEMO-") + (700_000 + nextFiling++));
    }

    private void caseRow(Case c, int version, String status, LocalDate filed, String reference) {
        Account a = c.account();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("case_id", c.id());
        row.put("version", version);
        row.put("case_type", c.type());
        row.put("brokerage_account", a.world.id());
        row.put("party_id", a.primary().id);
        row.put("status", status);
        row.put("alert_ids", c.alert());
        row.put("opened_on", c.opened());
        row.put("filing_type", c.filing());
        row.put("filing_due", c.filingDue());
        row.put("filed_on", filed);
        row.put("filing_reference", reference);
        row.put("business_date", day);
        row.put("event_time", now);
        emit(Feed.AML_CASES, row);
    }

    /** Runs a task at 07:15 on the business day {@code n} after today. */
    private void later(int n, Runnable run) {
        due.computeIfAbsent(businessDays(day, n), k -> new ArrayList<>()).add(run);
    }

    private LocalDate businessDays(LocalDate from, int n) {
        LocalDate d = from;
        for (int i = 0; i < n; i++) {
            d = calendar.next(d);
        }
        return d;
    }

    /** An execution of the order management system (item 151), for the next morning's surveillance. */
    void execution(Map<String, Object> row) {
        long account = (Long) row.get("brokerage_account");
        long security = (Long) row.get("security_id");
        symbols.put(security, (String) row.get("symbol"));
        long[] q = fills.computeIfAbsent((LocalDate) row.get("trade_date"), k -> new LinkedHashMap<>())
            .computeIfAbsent(List.of(account, security), k -> new long[2]);
        String side = (String) row.get("side");
        q[side.equals("buy") || side.equals("buy_to_cover") ? 0 : 1] += (Long) row.get("last_quantity");
    }

    /**
     * 07:15: the work due today (escalations, filings, reviews, complaints closed), then trade surveillance of the last
     * trade date (FINRA 5270 and wash-trade review): an account that bought and sold the same security that day.
     */
    void morning(Instant at, LocalDate today) {
        at(at, today);
        List<Runnable> work = due.remove(today);
        if (work != null) {
            for (int i = 0; i < work.size(); i++) {
                work.get(i).run();
            }
        }
        LocalDate last = calendar.previous(today);
        Map<List<Long>, long[]> traded = fills.remove(last);
        fills.keySet().removeIf(d -> d.isBefore(last));
        if (traded == null) {
            return;
        }
        for (Map.Entry<List<Long>, long[]> e : traded.entrySet()) {
            long[] q = e.getValue();
            if (q[0] == 0 || q[1] == 0) {
                continue;
            }
            Account a = accounts.get(e.getKey().get(0));
            long security = e.getKey().get(1);
            String id = "SURV-" + (42_000_000 + nextSurveillance++);
            surveillanceRow(id, 1, a, security, last, q, "open", null);
            String disposition = rng.chance(0.03) ? "escalated_to_compliance" : "no_further_action";
            later(1, () -> surveillanceRow(id, 2, a, security, last, q, "closed", disposition));
        }
    }

    private void surveillanceRow(String id, int version, Account a, long security, LocalDate tradeDate, long[] q,
                                 String status, String disposition) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("alert_id", id);
        row.put("version", version);
        row.put("model", "wash_trade");
        row.put("brokerage_account", a.world.id());
        row.put("security_id", security);
        row.put("symbol", symbols.get(security));
        row.put("trade_date", tradeDate);
        row.put("buy_quantity", q[0]);
        row.put("sell_quantity", q[1]);
        row.put("status", status);
        row.put("disposition", disposition);
        row.put("business_date", day);
        row.put("event_time", now);
        emit(Feed.SURVEILLANCE_ALERTS, row);
    }

    /**
     * 12:00: the day's changes to the parties (an address, a phone and email, an employer), rescreening a party whose
     * address changed; suitability reviews (FINRA 4512, at least every 36 months); new trusted contacts; complaints.
     */
    void servicing(Instant at, LocalDate today) {
        at(at, today);
        for (Account a : accounts.values()) {
            if (a.closed) {
                continue;
            }
            for (Party p : a.parties) {
                if (a.roles.get(p).equals("joint_owner") || a.roles.get(p).equals("trustee")
                    || a.roles.get(p).equals("authorized_trader")) {
                    continue;
                }
                double u = rng.uniform();
                if (u < 0.0015) {
                    City c = CITIES.get(rng.below(CITIES.size()));
                    p.address = street();
                    p.city = c.city();
                    p.state = c.state();
                    p.zip = c.zip();
                    p.version++;
                    party(p, "address_change", today);
                    screen("party_change", "party", String.valueOf(p.id), p.legalName, a, null, 0.01);
                } else if (u < 0.0025 && p.type.equals("individual")) {
                    p.email = email(p.first, p.last);
                    p.phone = p.phone.substring(0, 4) + "555-01" + String.format("%02d", rng.below(100));
                    p.version++;
                    party(p, "contact_change", today);
                } else if (u < 0.003 && p.type.equals("individual")) {
                    employment(p);
                    p.version++;
                    party(p, "employment_change", today);
                }
            }
            if (rng.chance(0.0008)) {
                suitability(a, a.world.persona());
                a.suitabilityVersion++;
                suitabilityRow(a, "periodic_review", today);
            }
            if (a.contactId == 0 && (a.registration.equals("individual") || a.registration.equals("joint")
                || a.registration.equals("ira")) && rng.chance(0.0003)) {
                trustedContact(a);
                contactRow(a, "added", today);
            } else if (a.contactId > 0 && rng.chance(0.0004)) {
                a.contactPhone = a.contactPhone.substring(0, 4) + "555-01" + String.format("%02d", rng.below(100));
                a.contactVersion++;
                contactRow(a, "phone_change", today);
            }
        }
        if (rng.chance(0.12)) {
            complaint();
        }
    }

    /** A written complaint (SEA 17a-3(a)(18), FINRA 4530), closed five to fifteen business days later. */
    private void complaint() {
        List<Account> open = accounts.values().stream().filter(a -> !a.closed).toList();
        Account a = open.get(rng.below(open.size()));
        String id = "CMP-" + (43_000_000 + nextComplaint++);
        String channel = rng.pick(List.of("email", "email", "web_form", "letter"));
        String category = rng.pick(List.of("service", "service", "fees", "transfer_delay", "execution_quality",
            "statement_error", "unauthorized_trading"));
        String product = category.equals("transfer_delay") || category.equals("fees") ? "account" : "equity";
        LocalDate received = day;
        complaintRow(id, 1, a, channel, category, product, "open", received, null, null);
        later(5 + rng.below(11), () -> complaintRow(id, 2, a, channel, category, product, "closed", received, day,
            rng.chance(0.7) ? "resolved" : "denied"));
    }

    private void complaintRow(String id, int version, Account a, String channel, String category, String product,
                              String status, LocalDate received, LocalDate closedOn, String disposition) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("complaint_id", id);
        row.put("version", version);
        row.put("brokerage_account", a.world.id());
        row.put("party_id", a.primary().id);
        row.put("channel", channel);
        row.put("category", category);
        row.put("product", product);
        row.put("status", status);
        row.put("received_on", received);
        row.put("closed_on", closedOn);
        row.put("disposition", disposition);
        row.put("business_date", day);
        row.put("event_time", now);
        emit(Feed.COMPLAINTS, row);
    }

    /**
     * 17:30: AML monitoring's structuring scenario over the day's cash deposits: three or more from $8,000 to under
     * $10,000 by one account in five business days raise an alert; the next morning it is escalated to a case, and four
     * business days later the SAR is filed, inside the 30 days 31 CFR 1023.320 allows.
     */
    void monitor(Instant at, LocalDate today) {
        at(at, today);
        LocalDate from = today;
        for (int i = 1; i < STRUCTURING_DAYS; i++) {
            from = calendar.previous(from);
        }
        LocalDate start = from;
        for (Map.Entry<Long, List<Deposit>> e : cashDeposits.entrySet()) {
            List<Deposit> window = e.getValue().stream().filter(d -> !d.day().isBefore(start))
                .filter(d -> d.amount().compareTo(STRUCTURING_LOW) >= 0 && d.amount().compareTo(CTR_THRESHOLD) < 0)
                .toList();
            LocalDate alerted = structuringAlerted.get(e.getKey());
            if (window.size() < STRUCTURING_DEPOSITS || !window.getLast().day().equals(today)
                || (alerted != null && !alerted.isBefore(start))) {
                continue;
            }
            structuringAlerted.put(e.getKey(), today);
            Account a = accounts.get(e.getKey());
            BigDecimal total = window.stream().map(Deposit::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            String detail = window.size() + " cash deposits from 8000 to under 10000 in " + STRUCTURING_DAYS
                + " business days: " + String.join(", ", window.stream().map(Deposit::payment).toList());
            String alert = alert("structuring", a, "open", total, detail, null, 1);
            LocalDate detected = today;
            later(1, () -> {
                String c = openCase("sar_review", a, alert, "sar", detected.plusDays(30));
                alert(alert, "structuring", a, "escalated", total, detail, c, 2);
                later(4, () -> fileCase(c));
            });
        }
        cashDeposits.values().forEach(list -> list.removeIf(d -> d.day().isBefore(start)));
    }

    /** When the seeded deposits are made: from 10:00 to 15:00. */
    LocalTime seededTime() {
        return LocalTime.of(10, 0).plusMinutes(rng.below(300));
    }
}
