// swift-tools-version:5.10
// Murmur for macOS. Builds with the Command Line Tools alone (no Xcode): `make app`.
import PackageDescription

let brew = "/opt/homebrew"

let package = Package(
    name: "Murmur",
    platforms: [.macOS(.v14)],
    products: [
        .executable(name: "Murmur", targets: ["MurmurApp"]),
        .executable(name: "murmur-cli", targets: ["murmur-cli"]),
    ],
    targets: [
        // whisper.cpp from Homebrew (`brew install whisper-cpp`), Metal backend.
        .systemLibrary(name: "CWhisper", path: "Sources/CWhisper"),
        .target(
            name: "MurmurCore",
            dependencies: ["CWhisper"],
            resources: [.copy("Resources/prompts"), .copy("Resources/catalog.json"), .copy("Resources/app_categories.json")],
            cSettings: [.unsafeFlags(["-I\(brew)/include"])],
            swiftSettings: [.unsafeFlags(["-Xcc", "-I\(brew)/include"])],
            linkerSettings: [.unsafeFlags(["-L\(brew)/opt/whisper-cpp/lib", "-L\(brew)/opt/ggml/lib", "-Xlinker", "-rpath", "-Xlinker", "\(brew)/opt/whisper-cpp/lib"])]
        ),
        .executableTarget(name: "MurmurApp", dependencies: ["MurmurCore"]),
        .executableTarget(name: "murmur-cli", dependencies: ["MurmurCore"]),
    ]
)
