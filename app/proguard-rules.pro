# SysMon release rules.
#
# Intentionally empty of keeps: every runtime-reflection path in this app is
# already covered, and each was verified against the shipped artifact rather
# than assumed.
#
#   - PowerProfile (BatteryMonitor, battery design capacity) reflects on a
#     FRAMEWORK class by string name. R8 neither renames nor strips android.jar
#     classes, so no keep rule here can help or hurt it.
#   - Glance, DataStore and ViewModel ship their own consumer rules; the applied
#     set is visible in build/outputs/mapping/release/configuration.txt.
#   - The FileProvider and the widget receiver are manifest-declared, which AGP
#     keeps automatically.
#
# One thing not to "fix": in the release DEX, RefreshSysMonWidgetAction appears
# to have lost its onAction method. That is correct output — R8 proved it the
# sole ActionCallback implementer and inlined the body into Glance's receiver.
# The widget refresh button works on minified builds (device-verified); adding
# a keep rule would only make the APK larger.
