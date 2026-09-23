class Probe {
    void check(String token, String hash) {
        if (token.equals("PROBE-NEW-FAKE-TOKEN-1234567890")) {}
        if (!hash.equals("0000000000000000000000000000000000000000000000000000000000000000")) {}
    }
}
