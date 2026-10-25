plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        create("gen") {
            id = "funecomp.gen"
            implementationClass = "GenPlugin"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
