plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        create("gen") {
            id = "funecomp.gen"
            implementationClass = "net.seilent.funecomp.logic.GenPlugin"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
