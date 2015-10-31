/* 
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.opengl

import java.io.PrintWriter
import org.lwjgl.generator.*
import java.util.regex.Pattern

val NativeClass.capName: String
	get() = if ( templateName.startsWith(prefixTemplate) ) {
		if ( prefix == "GL" )
			"OpenGL${templateName.substring(2)}"
		else
			templateName
	} else {
		"${prefixTemplate}_$templateName"
	}

private val CAPABILITIES_CLASS = "GLCapabilities"

val GLBinding = Generator.register(object: APIBinding(OPENGL_PACKAGE, CAPABILITIES_CLASS) {

	private val GLCorePattern = Pattern.compile("GL[1-9][0-9]")

	private val BufferOffsetTransform: FunctionTransform<Parameter> = object: FunctionTransform<Parameter>, SkipCheckFunctionTransform {
		override fun transformDeclaration(param: Parameter, original: String) = "long ${param.name}Offset"
		override fun transformCall(param: Parameter, original: String) = "${param.name}Offset"
	}

	private val Iterable<NativeClassFunction>.hasDeprecated: Boolean
		get() = this.any { it has DeprecatedGL }

	override fun generateAlternativeMethods(writer: PrintWriter, function: NativeClassFunction, transforms: MutableMap<QualifiedType, FunctionTransform<out QualifiedType>>) {
		val boParams = function.getParams { it has BufferObject && it.nativeType.mapping != PrimitiveMapping.POINTER }
		if ( boParams.any() ) {
			boParams.forEach { transforms[it] = BufferOffsetTransform }
			function.generateAlternativeMethod(writer, function.name, "Buffer object offset version of:", transforms)
			boParams.forEach { transforms.remove(it) }
		}
	}

	override fun printCustomJavadoc(writer: PrintWriter, function: NativeClassFunction, documentation: String): Boolean {
		if ( GLCorePattern.matcher(function.nativeClass.className).matches() ) {
			val xmlName = if ( function has ReferenceGL )
				function[ReferenceGL].function
			else
				function.stripPostfix(stripType = true)
			writer.printOpenGLJavaDoc(documentation, xmlName, function has DeprecatedGL)
			return true
		}
		return false
	}

	override fun printConstructorParams(writer: PrintWriter, nativeClass: NativeClass) {
		if ( nativeClass.functions.hasDeprecated )
			writer.print(", boolean fc")
	}

	override fun shouldCheckFunctionAddress(function: NativeClassFunction): Boolean = function.has(DeprecatedGL)

	override fun addParameterChecks(
		checks: MutableList<String>,
		mode: GenerationMode,
		parameter: Parameter,
		hasTransform: Parameter.(FunctionTransform<Parameter>) -> Boolean
	) {
		if ( !parameter.has(BufferObject) )
			return

		when {
			mode === GenerationMode.NORMAL -> "GLChecks.ensureBufferObject(${parameter[BufferObject].binding}, ${parameter.nativeType.mapping === PrimitiveMapping.POINTER});"
			parameter.nativeType.mapping !== PrimitiveMapping.POINTER -> "GLChecks.ensureBufferObject(${parameter[BufferObject].binding}, ${parameter.hasTransform(BufferOffsetTransform)});"
			else -> null
		}?.let {
			if ( !checks.contains(it) )
				checks.add(it)
		}
	}

	override fun getFunctionAddressCall(function: NativeClassFunction) =
		// Do the fc check here, because getFunctionAddress will return an address
		// even if the current context is forward compatible. We don't want that because
		// we prefer to throw an exception instead of letting GL raise an error and it's
		// also the only way to support the pseudo-fc mode.
		if ( function has DeprecatedGL )
			"GL.getFunctionAddress(provider, \"${function.nativeName}\", fc)"
		else
			super.getFunctionAddressCall(function);

	override fun PrintWriter.generateFunctionGetters(nativeClass: NativeClass) {
		println("\t// --- [ Function Addresses ] ---")

		println("""
	/** Returns the {@link ${nativeClass.className}} instance of the current context. */
	public static ${nativeClass.className} getInstance() {
		return getInstance(GL.getCapabilities());
	}

	/** Returns the {@link ${nativeClass.className}} instance of the specified {@link $CAPABILITIES_CLASS}. */
	public static ${nativeClass.className} getInstance($CAPABILITIES_CLASS caps) {
		return checkFunctionality(caps.__${nativeClass.className});
	}""")

		val functions = nativeClass.functions

		val hasDeprecated = functions.hasDeprecated

		print("\n\tstatic ${nativeClass.className} create(java.util.Set<String> ext, FunctionProvider provider")
		if ( hasDeprecated ) print(", boolean fc")
		println(") {")
		println("\t\tif ( !ext.contains(\"${nativeClass.capName}\") ) return null;")

		print("\n\t\t${nativeClass.className} funcs = new ${nativeClass.className}(provider")
		if ( hasDeprecated ) print(", fc")
		println(");")

		print("\n\t\tboolean supported = ")

		val printPointer = { func: NativeClassFunction ->
			if ( func has DependsOn )
				"${func[DependsOn].reference.let { if ( it.indexOf(' ') == -1 ) "ext.contains(\"$it\")" else it }} ? funcs.${func.simpleName} : -1L"
			else
				"funcs.${func.simpleName}"
		}

		if ( hasDeprecated ) {
			print("(fc || checkFunctions(")
			nativeClass.printPointers(this, printPointer) { it has DeprecatedGL }
			print(")) && ")
		}

		print("checkFunctions(")
		if ( hasDeprecated )
			nativeClass.printPointers(this, printPointer) { !(it has DeprecatedGL || it has IgnoreMissing) }
		else
			nativeClass.printPointers(this, printPointer) { !(it has IgnoreMissing) }

		println(");")

		print("\n\t\treturn GL.checkExtension(\"")
		print(nativeClass.capName);
		println("\", funcs, supported);")
		println("\t}\n")
	}

	override fun PrintWriter.generateContent() {
		println("/** Defines the capabilities of an OpenGL context. */")
		println("public final class $CAPABILITIES_CLASS {\n")

		val classes = super.getClasses { o1, o2 ->
			// Core functionality first, extensions after
			val isGL1 = o1.templateName.startsWith("GL")
			val isGL2 = o2.templateName.startsWith("GL")

			if ( isGL1 xor isGL2 )
				(if ( isGL1 ) -1 else 1)
			else
				o1.templateName.compareTo(o2.templateName, ignoreCase = true)
		}

		val classesWithFunctions = classes.filter { it.hasNativeFunctions }
		val alignment = classesWithFunctions.map { it.className.length }.fold(0) { left, right -> Math.max(left, right) }
		for ( extension in classesWithFunctions ) {
			print("\tfinal ${extension.className}")
			for ( i in 0..(alignment - extension.className.length - 1) )
				print(' ')
			println(" __${extension.className};")
		}

		println()
		classes.forEach {
			val documentation = it.documentation
			if ( documentation != null )
				println((if ( it.hasBody ) "When true, {@link ${it.className}} is supported." else documentation).toJavaDoc())
			println("\tpublic final boolean ${it.capName};")
		}

		println("\n\t$CAPABILITIES_CLASS(FunctionProvider provider, Set<String> ext, boolean fc) {")
		for ( extension in classes ) {
			val capName = extension.capName
			// TODO: Do not call create if the extension is not present. Reduces number of classes loaded (test with static init)
			if ( extension.hasNativeFunctions ) {
				print("\t\t$capName = (__${extension.className} = ${if ( capName == extension.className ) "$OPENGL_PACKAGE.${extension.className}" else extension.className}.create(ext, provider")
				if ( extension.functions.hasDeprecated ) print(", fc")
				println(")) != null;")
			} else
				println("\t\t$capName = ext.contains(\"${extension.capName}\");")
		}
		println("\t}")
		print("}")
	}

})

// DSL Extensions

fun String.nativeClassGL(
	templateName: String,
	nativeSubPath: String = "",
	prefix: String = "GL",
	prefixMethod: String = prefix.toLowerCase(),
	postfix: String = "",
	init: (NativeClass.() -> Unit)? = null
) = nativeClass(
	OPENGL_PACKAGE,
	templateName,
	nativeSubPath = nativeSubPath,
	prefix = prefix,
	prefixMethod = prefixMethod,
	postfix = postfix,
	binding = GLBinding,
	init = init
)

fun String.nativeClassWGL(templateName: String, postfix: String = "", init: (NativeClass.() -> Unit)? = null) =
	nativeClassGL(templateName, "wgl", "WGL", postfix = postfix, init = init)

fun String.nativeClassGLX(templateName: String, postfix: String = "", init: (NativeClass.() -> Unit)? = null) =
	nativeClassGL(templateName, "glx", "GLX", "glX", postfix, init)

private val REGISTRY_PATTERN = Pattern.compile("([A-Z]+)_(\\w+)")
val NativeClass.registryLink: String get() {
	val matcher = REGISTRY_PATTERN.matcher(templateName)
	if ( !matcher.matches() )
		throw IllegalStateException("Non-standard extension name: $templateName")
	return url("http://www.opengl.org/registry/specs/${matcher.group(1)}/${matcher.group(2)}.txt", templateName)
}

fun NativeClass.registryLink(prefix: String, name: String): String = registryLinkTo(prefix, name, templateName)
fun registryLinkTo(prefix: String, name: String, extensionName: String = "${prefix}_$name"): String =
	url("http://www.opengl.org/registry/specs/$prefix/$name.txt", extensionName)

val NativeClass.capLink: String get() = "$CAPABILITIES_CLASS##$capName"
val NativeClass.core: String get() = "{@link ${this.className} OpenGL ${this.className[2]}.${this.className[3]}}"
val NativeClass.glx: String get() = "{@link ${this.className} GLX ${this.className[3]}.${this.className[4]}}"
val NativeClass.promoted: String get() = "Promoted to core in ${this.core}."