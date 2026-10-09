// Build-tool dependencies only: none of these ship in the APK. AGP 9.4.1 still pulls in versions with
// known CVEs, so force patched ones. Drop each constraint once AGP's own version is at least as new.
buildscript {
    dependencies {
        constraints {
            classpath("org.bouncycastle:bcprov-jdk18on:1.86") {
                because("CVE fixes in 1.80.2, 1.84 and 1.85 (Dependabot #21, #38, #53, #54); AGP 9.4.1 has 1.80.2")
            }
            classpath("org.bouncycastle:bcpkix-jdk18on:1.86") {
                because("CVE fixed in 1.84 (Dependabot #20); kept in step with bcprov")
            }
            classpath("org.bouncycastle:bcutil-jdk18on:1.86") {
                because("kept in step with bcprov and bcpkix")
            }
            classpath("org.bitbucket.b_c:jose4j:0.9.7") {
                because("CVE fixed in 0.9.6 (Dependabot #17); AGP 9.4.1 has 0.9.5")
            }
            classpath("org.jdom:jdom2:2.0.6.1") {
                because("XXE CVE fixed in 2.0.6.1 (Dependabot #14); AGP 9.4.1 has 2.0.6")
            }
            classpath("org.apache.commons:commons-lang3:3.21.0") {
                because("CVE fixed in 3.18.0 (Dependabot #10); AGP 9.4.1 has 3.16.0")
            }
            classpath("org.apache.httpcomponents:httpclient:4.5.14") {
                because("CVE fixed in 4.5.13 (Dependabot #1); AGP 9.4.1 also asks for 4.5.6")
            }
            classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20") {
                because("CVE fixed in 2.4.20 (Dependabot #49); AGP 9.4.1's built-in Kotlin uses 2.2.10")
            }
        }
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
}
