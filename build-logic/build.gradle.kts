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
