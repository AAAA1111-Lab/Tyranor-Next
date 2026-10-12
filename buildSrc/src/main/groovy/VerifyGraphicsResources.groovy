import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import java.security.MessageDigest

/** Verify committed local artifacts against their contribution's provenance, without downloading sources. */
abstract class VerifyGraphicsResources extends DefaultTask {
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getManifestsDirectory()
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getArtifacts()
    @Internal abstract DirectoryProperty getEngineDirectory()

    @TaskAction void verify() {
        File engine = engineDirectory.get().asFile.canonicalFile
        Set<File> inputs = artifacts.files.collect { it.canonicalFile } as Set
        manifestsDirectory.get().asFile.eachFileRecurse { manifest ->
            if (!manifest.isFile() || !manifest.name.endsWith('.json')) return
            def data = new JsonSlurper().parse(manifest)
            (data.generated ?: []).each { item ->
                verifyFile(engine, inputs, new File(engine, 'src/main/assets/' + item.file), item.sha256)
            }
            if (data.files != null) {
                if (!(data.assetDirectory instanceof String)) throw new GradleException('Missing assetDirectory: ' + manifest)
                data.files.each { item ->
                    verifyFile(engine, inputs, new File(engine, 'src/main/assets/' + data.assetDirectory + '/' + item.file), item.sha256)
                }
            }
            (data.patches ?: []).each { item ->
                verifyFile(engine, inputs, new File(manifest.parentFile, item.file), item.sha256)
            }
            if (data.previousExportsSha256 != null) {
                verifyFile(engine, inputs, new File(engine.parentFile, data.previousExportsFile), data.previousExportsSha256)
            }
            if (data.librarySha256 != null) {
                verifyFile(engine, inputs, new File(engine.parentFile, data.libraryFile), data.librarySha256)
            }
        }
    }
    private static void verifyFile(File engine, Set<File> inputs, File file, Object expected) {
        File target = file.canonicalFile
        if (!target.toPath().startsWith(engine.toPath()) || !inputs.contains(target) || !target.isFile()
                || !(expected instanceof String) || !(expected ==~ /[0-9a-f]{64}/)) {
            throw new GradleException('Invalid graphics artifact record: ' + file)
        }
        MessageDigest sha = MessageDigest.getInstance('SHA-256')
        target.withInputStream { stream ->
            byte[] buffer = new byte[65536]
            int count
            while ((count = stream.read(buffer)) != -1) sha.update(buffer, 0, count)
        }
        if (sha.digest().encodeHex().toString() != expected) {
            throw new GradleException('Graphics artifact SHA-256 mismatch: ' + target)
        }
    }
}
