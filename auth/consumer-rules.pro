# AndroidViewModelFactory constructs this application-scoped model reflectively.
-keepclassmembers class tracewall.auth.AuthViewModel {
    public <init>(android.app.Application);
}
