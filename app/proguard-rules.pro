# libxposed 模块入口与 Hook 类不能被裁剪
-keep class com.example.syshelperblocker.** { *; }
-keep class io.github.libxposed.api.** { *; }
