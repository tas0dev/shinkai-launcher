plugins {
	java
	application
	id("com.github.johnrengelman.shadow") version "7.1.2"
	id("com.palantir.git-version") version "0.13.0"
	id("com.github.breadmoirai.github-release") version "2.4.1"
	kotlin("jvm") version "1.7.10"
	id("com.github.jk1.dependency-license-report") version "2.0"
	`maven-publish`
}

java {
	sourceCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach {
	options.encoding = "UTF-8"
}

repositories {
	mavenCentral()
	google()
	maven {
		url = uri("https://jitpack.io")
	}
}

val r8 by configurations.creating
val distJarOutput by configurations.creating {
	isCanBeResolved = false
	isCanBeConsumed = true

	attributes {
		attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
		attribute(Bundling.BUNDLING_ATTRIBUTE, project.objects.named(Bundling::class.java, Bundling.EMBEDDED))
	}
}

dependencies {
	implementation("commons-cli:commons-cli:1.5.0")
	implementation("com.google.code.gson:gson:2.9.0")
	implementation("com.squareup.okio:okio:3.1.0")
	implementation(kotlin("stdlib-jdk8"))
	implementation("com.squareup.okhttp3:okhttp:4.10.0")
	implementation("cc.ekblad:4koma:1.1.0")
	implementation("org.bouncycastle:bcprov-jdk15to18:1.86")
	testImplementation("junit:junit:4.13.2")

	r8("com.android.tools:r8:3.3.28")
}

application {
	mainClass.set("link.infra.packwiz.installer.ShinaiLauncher")
}

tasks.processResources {
	dependsOn("generateLicenseReport")
	from(layout.buildDirectory.file("reports/dependency-license/licenses.md")) {
		into("META-INF")
		rename { "DEPENDENCY-LICENSES.md" }
	}
	providers.gradleProperty("shinkaiSigningPublicKey").orNull?.let { keyPath ->
		val key = file(keyPath)
		inputs.property("shinkaiSigningPublicKeyPath", keyPath)
		doFirst {
			check(key.isFile) { "Shinkai signing public key not found: $key" }
			val ed25519DerHeader = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
			check(key.length() == 44L && key.readBytes().copyOfRange(0, ed25519DerHeader.size).contentEquals(ed25519DerHeader)) {
				"Expected an Ed25519 public key in SubjectPublicKeyInfo DER format: $key"
			}
		}
		from(key) {
			into("link/infra/packwiz/installer")
			rename { "shinkai-pack-signing.der" }
		}
	}
	from("docs/assets") {
		include("YoungSerif-Regular.ttf", "YoungSerif-OFL.txt", "evergreen-valley.jpg")
		into("link/infra/packwiz/installer/ui")
	}
}

val gitVersion: groovy.lang.Closure<*> by extra
version = gitVersion()

tasks.jar {
	manifest {
		attributes["Main-Class"] = application.mainClass.get()
		attributes["Implementation-Version"] = project.version
	}
}

licenseReport {
	renderers = arrayOf<com.github.jk1.license.render.ReportRenderer>(
		com.github.jk1.license.render.InventoryMarkdownReportRenderer("licenses.md", "packwiz-installer")
	)
	filters = arrayOf<com.github.jk1.license.filter.DependencyFilter>(com.github.jk1.license.filter.LicenseBundleNormalizer())
}

tasks.shadowJar {
	// 4koma uses kotlin-reflect; requires Kotlin metadata
	//exclude("**/*.kotlin_metadata")
	//exclude("**/*.kotlin_builtins")
	exclude("META-INF/maven/**/*")
	exclude("META-INF/proguard/**/*")
	// Signatures from dependencies no longer match after shading and shrinking.
	exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")
	// The lightweight Ed25519 API doesn't require registration as a JCA provider.
	exclude("META-INF/services/java.security.Provider")

	// Relocate Commons CLI, so that it doesn't clash with old packwiz-installer-bootstrap (that shades it)
	relocate("org.apache.commons.cli", "link.infra.packwiz.installer.deps.commons-cli")

	// from Commons CLI
	exclude("META-INF/LICENSE.txt")
	exclude("META-INF/NOTICE.txt")
}

val shrinkJar by tasks.registering(JavaExec::class) {
	val rules = file("src/main/proguard.txt")
	val r8File = base.libsDirectory.file(provider {
		base.archivesName.get() + "-" + project.version + "-all-shrink.jar"
	})
	dependsOn(configurations.named("runtimeClasspath"))
	inputs.files(tasks.shadowJar, rules)
	outputs.file(r8File)

	classpath(r8)
	mainClass.set("com.android.tools.r8.R8")
	args = mutableListOf(
		"--release",
		"--classfile",
		"--output", r8File.get().toString(),
		"--pg-conf", rules.toString(),
		"--lib", System.getProperty("java.home"),
		*(if (System.getProperty("java.version").startsWith("1.")) {
			// javax.crypto, necessary on <1.9 for compiling Okio
			arrayOf("--lib", System.getProperty("java.home") + "/lib/jce.jar")
		} else { arrayOf() }),
		tasks.shadowJar.get().archiveFile.get().asFile.toString()
	)
}

// MANIFEST.MF must be one of the first 2 entries in the zip for JarInputStream to see it
// Gradle's JAR creation handles this whereas R8 doesn't, so the dist JAR is repacked
val distJar by tasks.registering(Jar::class) {
	from(shrinkJar.map { zipTree(it.outputs.files.singleFile) })
	archiveClassifier.set("all-repacked")
	manifest {
		from(shrinkJar.map { zipTree(it.outputs.files.singleFile).matching {
			include("META-INF/MANIFEST.MF")
		}.singleFile })
	}
}

artifacts {
	add("distJarOutput", distJar) {
		classifier = "dist"
	}
}

// Used for vscode launch.json
val copyJar by tasks.registering(Copy::class) {
	from(distJar)
	rename("packwiz-installer-(.*)\\.jar", "packwiz-installer.jar")
	into(layout.buildDirectory.dir("dist"))
	outputs.file(layout.buildDirectory.dir("dist").map { it.file("packwiz-installer.jar") })
}

tasks.build {
	dependsOn(copyJar)
}

val bootstrap by sourceSets.creating
val bootstrapTools by sourceSets.creating
val bootstrapTest by sourceSets.creating
listOf(bootstrapTools, bootstrapTest).forEach {
	dependencies.add(it.implementationConfigurationName, bootstrap.output)
}
dependencies.add(bootstrapTest.implementationConfigurationName, bootstrapTools.output)
listOf(bootstrap, bootstrapTools, bootstrapTest).forEach {
	tasks.named<JavaCompile>(it.compileJavaTaskName) { options.release.set(17) }
}

val shinkaiReleaseDirectory = layout.buildDirectory.dir("shinkai-release")
val shinkaiBootstrap by tasks.registering(Jar::class) {
	from(bootstrap.output)
	archiveFileName.set("shinkai-bootstrap.jar")
	destinationDirectory.set(shinkaiReleaseDirectory)
	manifest.attributes["Main-Class"] = "shinkai.bootstrap.Bootstrap"
	isPreserveFileTimestamps = false
	isReproducibleFileOrder = true
}

tasks.register<JavaExec>("testShinkaiBootstrap") {
	classpath = bootstrapTest.runtimeClasspath
	mainClass.set("shinkai.bootstrap.BootstrapTest")
}

tasks.register<JavaExec>("signShinkaiLauncher") {
	dependsOn(shinkaiBootstrap, distJar)
	classpath = bootstrapTools.runtimeClasspath
	mainClass.set("shinkai.bootstrap.SignRelease")
	doFirst {
		check(providers.gradleProperty("shinkaiSigningPublicKey").isPresent) { "Set -PshinkaiSigningPublicKey to the MOD pack public key" }
		val signingKey = providers.gradleProperty("shinkaiLauncherSigningPrivateKey").orNull
			?: error("Set -PshinkaiLauncherSigningPrivateKey to the separate launcher release signing key")
		val sequence = providers.gradleProperty("shinkaiReleaseSequence").orNull
			?: error("Set -PshinkaiReleaseSequence to the number from the shinkai-v<N> release tag")
		args(sequence, distJar.get().archiveFile.get().asFile.absolutePath,
			file(signingKey).absolutePath, shinkaiReleaseDirectory.get().asFile.absolutePath)
	}
}

githubRelease {
	owner("comp500")
	repo("packwiz-installer")
	tagName("${project.version}")
	releaseName("Release ${project.version}")
	draft(true)
	token(findProperty("github.token") as String?)
	releaseAssets(layout.buildDirectory.dir("dist").map { it.file("packwiz-installer.jar") }.get())
}

tasks.githubRelease {
	dependsOn(copyJar)
	enabled = project.hasProperty("github.token") && project.findProperty("release") == "true"
}

tasks.publish {
	dependsOn(tasks.githubRelease)
}

tasks.compileKotlin {
	kotlinOptions {
		jvmTarget = "1.8"
		freeCompilerArgs = listOf("-Xjvm-default=all", "-Xallow-result-return-type", "-opt-in=kotlin.io.path.ExperimentalPathApi", "-Xlambdas=indy")
	}
}
tasks.compileTestKotlin {
	kotlinOptions {
		jvmTarget = "1.8"
		freeCompilerArgs = listOf("-Xjvm-default=all", "-Xallow-result-return-type", "-opt-in=kotlin.io.path.ExperimentalPathApi", "-Xlambdas=indy")
	}
}

val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.addVariantsFromConfiguration(distJarOutput) {
	mapToMavenScope("runtime")
	mapToOptional()
}
javaComponent.withVariantsFromConfiguration(configurations["shadowRuntimeElements"]) {
	skip()
}

if (project.hasProperty("bunnycdn.token")) {
	publishing {
		publications {
			create<MavenPublication>("maven") {
				groupId = "link.infra.packwiz"
				artifactId = "packwiz-installer"

				from(components["java"])
			}
		}
		repositories {
			maven {
				url = if (project.findProperty("release") == "true") {
					uri("https://storage.bunnycdn.com/comp-maven/repository/release")
				} else {
					uri("https://storage.bunnycdn.com/comp-maven/repository/snapshot")
				}
				credentials(HttpHeaderCredentials::class) {
					name = "AccessKey"
					value = findProperty("bunnycdn.token") as String?
				}
				authentication {
					create<HttpHeaderAuthentication>("header")
				}
			}
		}
	}
}

