# HiveMQ MQTT client uses Netty; keep reflection-accessed classes when minifying.
-dontwarn io.netty.**
-dontwarn org.slf4j.**
-keep class com.hivemq.client.** { *; }
-keep class io.netty.** { *; }
