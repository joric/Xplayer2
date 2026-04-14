// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// fix illegal unicode escape sequence
subprojects {
  tasks.withType<JavaCompile>().configureEach {
    doFirst {
      fileTree(rootDir).matching { include("**/build/generated/aidl_source_output_dir/**/*.java") }
        .forEach {
          val s = it.readText(); val r = s.replace('\\','/')
          if (r != s) it.writeText(r)
        }
    }
  }
}
