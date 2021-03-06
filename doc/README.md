# PROJECT STRUCTURE
LWJGL is organized in modules, described below:

### Core
The LWJGL core.
* modules/core/src/main/c
* modules/core/src/main/include
* modules/core/src/main/java
* modules/core/src/generated/c
* modules/core/src/generated/java
* modules/core/src/test/java (unit tests and demo/tutorial code)

Dependencies: n/a (but the Generator has to execute successfully first)
Library Dependencies: n/a
Test Library Dependencies: TestNG, JCommander

### Utilities
Optional LWJGL components and helper functionality.
* modules/util/src/main/java

Dependencies: Core
Library Dependencies: n/a

### Templates
The source code Generator and the templates it uses to define the native bindings.
* modules/templates/src/main/java
* modules/templates/src/main/kotlin

Dependencies: n/a (except a copy org.lwjgl.PointerBuffer)
Library Dependencies: Kotlin runtime

# INSTALLATION
Requirements:
* JDK 6 or newer  
    See *JAVA6_HOME* below for more info.
* Ant 1.9.3 or newer

Step-by-step:

* git clone https://github.com/LWJGL/lwjgl3.git
* cd lwjgl3
* ant init
* ant init-generated (optional but highly recommended if you work on bindings)
* ant init-wiki (optional, only required if you work on the wiki)

At this point you're ready to follow the build process explained below.

LWJGL comes with a preconfigured **IntelliJ IDEA** project. You can use the Community Edition with the Kotlin and Ant plugins and, optionally, the TestNG and Copyright plugins.
* File &gt; Open &gt; choose the */config/ide/idea* folder
* File &gt; Project Structure &gt; Project &gt; choose or create the Project SDK
* If you haven't used the init-generated and init-wiki targets, either ignore the VCS errors, or go to Settings &gt; Version Control &gt; remove the missing directories from the list of VCS roots.

LWJGL also comes with an **Eclipse** project. Copy the project files from the [eclipse](https://github.com/LWJGL/lwjgl3/tree/master/config/ide/eclipse) folder into the root directory and open it as an Eclipse file. There's also a Kotlin plugin for Eclipse available now, see [Getting Started with Eclipse](http://kotlinlang.org/docs/tutorials/getting-started-eclipse.html).

LWJGL does not yet provide a project for **Netbeans**, but it should be straightforward to configure, assuming you follow the project structure explained above.

# BUILD PROCESS
LWJGL uses Ant for the build process, which goes like so:
* ant compile-templates (compiles the Generator)
* ant generate (runs the Generator)
* ant compile (compiles the Java source code)
* ant compile-native (compiles the native code for the target platform)
* ant tests (runs the test suite)
* ant demo -Dclass=&lt;*classpath to demo*&gt; (runs the demo specified by the *class* property)

# GENERATOR
LWJGL uses the **Generator** in the Templates module to automatically generate native code bindings. The Generator uses template files as input. Both the Generator itself and the template files are written in Kotlin, which is a new JVM-based language, more info [here](http://kotlinlang.org/). The Generator defines a handy DSL that the templates use to define the native code structure.

* Generator source: modules/templates/src/main/kotlin/org/lwjgl/generator
* Template configuration: modules/templates/src/main/kotlin/org/lwjgl/&lt;**PACKAGE**&gt;
* Template source: modules/templates/src/main/kotlin/org.lwjgl/&lt;**PACKAGE**&gt;/templates

The Generator is very aggressive with skipping work during the generation process. It does that by comparing timestamps of the input template source and the output Java source files. The output file timestamp is also compared against the timestamp of the latest change in the Generator source. Even when all attemps to skip generation fail, the generation happens in-memory and the output file contents are compared against the new content. Only when something has changed is the file overwritten.

The benefit of all that is reduced native code compilation times. If, for any reason, the incremental generation causes problems, use one or more of the ant clean-* targets.

# BUILD CONFIGURATION
The config folder contains the LWJGL configuration.
* ANT
	- config/build-assets.xml: Demo assets
	- config/build-bindings.xml: Bindings configuration
	- config/build-definitions.xml: Reusable definitions and utilities
	- config/<platform>/build.xml: Platform-specific definitions
* TestNG
	- config/tests.xml
	- a config/tests_<platform>.xml per platform

The ANT build can be configured with the following environment variables:
* JAVA6_HOME (optional, recommended)  
	Should point to a JDK 6. This is used to configure the javac *bootclasspath* to ensure that the source code is compatible with Java 6. This is only useful if you plan to make changes to the LWJGL source code.
* LWJGL_BUILD_TYPE (optional)  
	This is used as the source of binary dependencies. Valid values:
   - *nightly*  
       the latest successful build. Dependency repos can be found [here](https://github.com/LWJGL-CI). This is the default.
   - *stable*  
       the latest nightly build that has been verified to work with LWJGL.
   - *release/latest*  
       the latest stable build that has been promoted to an official LWJGL release.
   - *release/{build.version}*  
       a specific previously released build.
* LWJGL_BUILD_ARCH (optional)  
	The target native architecture. Must be either x86 or x64. By default, os.arch of the JVM that runs ANT is used, but this can be overriden for cross-compiling to another architecture.
* LWJGL_BUILD_OFFLINE (optional)  
	Offline build flag. This is useful when working offline, or when custom binary dependencies are used (so they are not overriden). Set to one of true/on/yes to enable.
* LWJGL_BUILD_OUTPUT (optional)  
	Overrides the default output directory. By default, the directories /bin, /generated and /release will be created in the same directory as the main build script. These 3 directories will contain thousands of tiny files, so you may want to override their location due to performance characteristics of the storage hardware.  
Note that when this property is set, the directories /bin, /generated and /release will be symlinks to the corresponding directories in LWJGL_BUILD_OUTPUT. The ant scripts and IDE projects always work with paths relative to the project root.

# RUNTIME CONFIGURATION
LWJGL can be configured at runtime with system properties. There are two types of properties:
* STATIC  
    They are read once per JVM process, usually during class initialization. Their values are stored in "static final" variables and usually related to statically turn features on or off.
* DYNAMIC  
    These may be read once or more times, changing LWJGL's behavior dynamically.

The supported options can be found in the [Configuration](https://github.com/LWJGL/lwjgl3/blob/master/modules/core/src/main/java/org/lwjgl/system/Configuration.java) class. This class can also be used to set the option values programmatically.

# LIBRARY DEPENDENCIES
* Kotlin
    - libs/kotlinc
* TestNG
    - libs/testng.jar
    - libs/jcommander.jar

# CODE STYLE
Tab-size: 4 spaces  
Right margin: 160 chars