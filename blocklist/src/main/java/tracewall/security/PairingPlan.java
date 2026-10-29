package tracewall.security;

/** Determines a safe retry without overwriting an existing membership. */
public final class PairingPlan {
    public enum Action { REGISTER_BOTH, REGISTER_REALTIME, REGISTER_FIRESTORE, COMPLETE, CHILD_RESET_REQUIRED }
    private PairingPlan() {}

    public static Action next(String realtimeCode, String firestoreCode, String presentedCode) {
        if (realtimeCode != null && firestoreCode != null) return Action.COMPLETE;
        if ((realtimeCode != null && !realtimeCode.equals(presentedCode))
                || (firestoreCode != null && !firestoreCode.equals(presentedCode)))
            return Action.CHILD_RESET_REQUIRED;
        if (realtimeCode != null) return Action.REGISTER_FIRESTORE;
        if (firestoreCode != null) return Action.REGISTER_REALTIME;
        return Action.REGISTER_BOTH;
    }
}
