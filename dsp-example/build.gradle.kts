import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
}

// A standalone APK that hosts the example DSP plug-in. The user installs it
// separately; Lydex discovers it via PackageManager and reads the .wasm out
// of assets/. The .wasm is built from ./rust — see README.

val pluginVersionName = "0.1.0"
val wasmSourcePath =
    "${projectDir}/rust/target/wasm32-unknown-unknown/release/lydex_dsp_example.wasm"

android {
    namespace = "dev.lydex.plugins.example"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.lydex.plugins.example"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = pluginVersionName
    }

    base {
        archivesName.set("lydex-dsp-example-$pluginVersionName")
    }
}

// Stage the pre-built .wasm into the APK's assets/. Build it first with:
//   cd dsp-example/rust && cargo build --release --target wasm32-unknown-unknown
abstract class StageWasmTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val sourceWasm: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val src = sourceWasm.get().asFile
        if (!src.exists()) {
            throw GradleException(
                "Plug-in .wasm not found at:\n  ${src.absolutePath}\n\n" +
                    "Build it first:\n  cd dsp-example/rust && " +
                    "cargo build --release --target wasm32-unknown-unknown",
            )
        }
        val out = outputDir.get().asFile
        out.mkdirs()
        src.copyTo(out.resolve("lydex_dsp_example.wasm"), overwrite = true)
    }
}

val stageWasm = tasks.register<StageWasmTask>("stageWasmAsset") {
    sourceWasm.set(file(wasmSourcePath))
    outputDir.set(layout.buildDirectory.dir("generated/wasm-asset"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(stageWasm) { it.outputDir }
    }
}
