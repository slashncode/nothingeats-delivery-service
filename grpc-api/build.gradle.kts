// The delivery service's gRPC contract: protos and generated Kotlin stubs. Consumers include this
// repository as a composite build and depend on io.github.slashncode.nothingeats.delivery:grpc-api.
plugins {
    id("nothingeats.kotlin-base")
    id("nothingeats.grpc")
}

group = "io.github.slashncode.nothingeats.delivery"

// A service may use several contracts; their jars must not all be called grpc-api.jar.
base {
    archivesName = "delivery-grpc-api"
}

dependencies {
    implementation(platform(libs.nothingeats.platform.bom))
}
