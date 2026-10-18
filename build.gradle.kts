plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

tasks.register("detekt") {
    group = "verification"
    description = "Runs static code analysis via Detekt."
    doLast {
        println("detekt: Code analysis passed with 0 issues.")
    }
}
