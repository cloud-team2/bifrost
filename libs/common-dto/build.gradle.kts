plugins {
    `java-library`
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-annotations:2.17.2")
    api("jakarta.validation:jakarta.validation-api:3.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("org.assertj:assertj-core:3.26.3")
}
