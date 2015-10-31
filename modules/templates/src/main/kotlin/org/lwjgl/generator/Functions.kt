/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.generator

import org.lwjgl.generator.GenerationMode.ALTERNATIVE
import org.lwjgl.generator.GenerationMode.NORMAL
import org.lwjgl.generator.ParameterType.*
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.util.*

/*
	****
	The code below implements the more complex parts of LWJGL's code generation.
	Please only modify if you fully understand what's going on and you know
	what you're doing.
	****

	The basic generation is relatively straightforward. It's an almost 1-to-1 mapping
	of the native function signature to the proper Java -> JNI -> native function code.

	We then try to generate additional Java methods that make the user's life easier. We
	use the TemplateModifiers on the function signature parameters and return values to figure
	out what kind of FunctionTransforms we should apply. Depending on the modifiers, we
	may generate one or more additional methods.
*/

// Global definitions

internal val ADDRESS = "address()"

internal val RESULT = "__result"
internal val POINTER_POSTFIX = "Address"
internal val MAP_OLD = "old_buffer"
internal val MAP_LENGTH = "length"
internal val FUNCTION_ADDRESS = "__functionAddress"

internal val API_BUFFER = "__buffer"
internal val JNIENV = "__env"

enum class GenerationMode {
	NORMAL,
	ALTERNATIVE
}

abstract class Function(
	val returns: ReturnValue,
	val simpleName: String,
	val name: String = simpleName,
	val documentation: String,
	vararg val parameters: Parameter
) : TemplateElement() {

	protected val paramMap: Map<String, Parameter> = {
		val map = HashMap<String, Parameter>()
		for (param in parameters)
			map.put(param.name, param)
		map
	}();

	protected val hasNativeParams: Boolean = getNativeParams().any()

	fun getParam(paramName: String) = paramMap[paramName] ?: throw IllegalArgumentException("Referenced parameter does not exist: $simpleName.$paramName")
	fun getParams(predicate: (Parameter) -> Boolean) = parameters.asSequence().filter(predicate)
	fun getParam(predicate: (Parameter) -> Boolean) = getParams(predicate).single()
	fun hasParam(predicate: (Parameter) -> Boolean) = getParams(predicate).any()

	fun getNativeParams() = getParams { !it.has(Virtual) }

	/** Returns a parameter that has the specified ReferenceModifier with the specified reference. Returns null if no such parameter exists. */
	fun getReferenceParam(modifierObject: ModifierKey<*>, reference: String) = getParams {
		it.hasRef(modifierObject, reference)
	}.firstOrNull() // Assumes at most 1 parameter will be found that references the specified parameter

}

// DSL extensions

fun NativeType.IN(name: String, javadoc: String, links: String = "", linkMode: LinkMode = LinkMode.SINGLE) = Parameter(this, name, IN, javadoc, links, linkMode)
fun PointerType.OUT(name: String, javadoc: String, links: String = "", linkMode: LinkMode = LinkMode.SINGLE) = Parameter(this, name, OUT, javadoc, links, linkMode)
fun PointerType.INOUT(name: String, javadoc: String, links: String = "", linkMode: LinkMode = LinkMode.SINGLE) = Parameter(this, name, INOUT, javadoc, links, linkMode)

private fun <T> PrintWriter.printList(items: Sequence<T>, itemPrint: (item: T) -> String?) = print(items.map(itemPrint).filterNotNull().joinToString(", "))

// --- [ Native class functions ] ---

class NativeClassFunction(
	returns: ReturnValue,
	simpleName: String,
	name: String,
	documentation: String,
	val nativeClass: NativeClass,
	vararg parameters: Parameter
) : Function(returns, simpleName, name, documentation, *parameters) {

	init {
		validate();
		if ( nativeClass.binding != null )
			JNI.register(this)
	}

	val nativeName: String get() = if ( has(NativeName) ) this[NativeName].name else name

	val accessModifier: String
		get() = (if ( has(AccessModifier) ) this[AccessModifier].access else nativeClass.access).modifier

	fun stripPostfix(functionName: String = name, stripType: Boolean): String {
		if ( !hasNativeParams )
			return functionName

		val param = parameters[parameters.lastIndex]
		if ( !param.isBufferPointer )
			return functionName

		var name = functionName
		var postfix = (if ( has(DependsOn) ) this[DependsOn].postfix else null) ?: nativeClass.postfix
		if ( name.endsWith(postfix) )
			name = name.substring(0, name.length - postfix.length)
		else
			postfix = ""

		var cutCount = if ( name.endsWith("v") ) {
			if ( name.endsWith("_v") ) 2 else 1
		} else
			0

		if ( stripType ) {
			val pointerMapping = param.nativeType.mapping as PointerMapping
			val typeChar = when ( pointerMapping ) {
				PointerMapping.DATA_SHORT  -> "s"
				PointerMapping.DATA_INT    -> "i"
				PointerMapping.DATA_LONG   -> "i64"
				PointerMapping.DATA_FLOAT  -> "f"
				PointerMapping.DATA_DOUBLE -> "d"
				else                       -> ""
			}

			if ( typeChar != "" ) {
				val offset = name.length - cutCount
				if ( typeChar.equals(name.substring(offset - typeChar.length, offset)) )
					cutCount += typeChar.length

				if ( name[name.length - cutCount - 1] == 'u' )
					cutCount++
			}
		}

		return name.substring(0, name.length - cutCount) + postfix
	}

	val javaDocLink: String
		get() = "${nativeClass.className}$methodLink"

	private val methodLink: String get() = "#$simpleName()"

	val hasCustomJNI: Boolean
		get() = nativeClass.binding == null || (has(Code) && this[Code].let { it.nativeBeforeCall != null || it.nativeCall != null || it.nativeAfterCall != null })

	private val isSimpleFunction: Boolean
		get() = nativeClass.binding == null && !(isSpecial || returns.isSpecial || hasParam { it.isSpecial })

	private val hasUnsafeMethod: Boolean
		get() = nativeClass.binding != null && (returns.isBufferPointer || hasParam { it.isBufferPointer }) && !returns.has(Address)

	private val ReturnValue.isStructValue: Boolean
		get() = nativeType is StructType && !nativeType.includesPointer

	internal val returnsStructValue: Boolean
		get() = returns.isStructValue && !hasParam { it has AutoSizeResult }

	private val returnsJavaMethodType: String
		get() = if ( returnsStructValue ) "void" else returns.javaMethodType

	private val returnsNativeMethodType: String
		get() = if ( returnsStructValue ) "void" else returns.nativeMethodType

	private val returnsJniFunctionType: String
		get() = if ( returnsStructValue ) "void" else returns.jniFunctionType

	private fun hasAutoSizePredicate(reference: Parameter): (Parameter) -> Boolean = { it has AutoSize && it[AutoSize].hasReference(reference.name) }

	private fun hasAutoSizeFor(reference: Parameter) = hasParam(hasAutoSizePredicate(reference))

	val hideAutoSizeResultParam: Boolean
		get() = returns.nativeType is PointerType && getParams { it.isAutoSizeResultOut }.count() == 1

	private fun Parameter.error(msg: String) {
		throw IllegalArgumentException("$msg [${nativeClass.className}.${this@NativeClassFunction.name}, parameter: ${this.name}]")
	}

	/** Validates parameters with modifiers that reference other parameters. */
	private fun validate() {
		var returnCount = 0
		parameters.forEach {
			if ( it has AutoSize ) {
				val autoSize = it[AutoSize]
				(sequenceOf(autoSize.reference) + autoSize.dependent.asSequence()).forEach { reference ->
					val bufferParam = paramMap[reference]
					if ( bufferParam == null )
						it.error("Buffer reference does not exist: AutoSize($reference)")
					else {
						when {
							bufferParam.nativeType !is PointerType
							                             -> it.error("Buffer reference must be a pointer type: AutoSize($reference)")
							!bufferParam.isBufferPointer -> it.error("Buffer reference must not be a opaque pointer: AutoSize($reference)")
							bufferParam.nativeType is StructType && !bufferParam.has(StructBuffer)
							                             -> it.error("Struct reference must be annotated with StructBuffer: AutoSize($reference)")
						}

						if ( bufferParam.nativeType is CharSequenceType && bufferParam.nativeType.charMapping == CharMapping.UTF16 )
							it.replaceModifier(AutoSize(2, autoSize.reference, *autoSize.dependent, applyTo = autoSize.applyTo))
					}
				}
			}

			if ( it has AutoSizeResult ) {
				if ( !returns.nativeType.isPointerData )
					it.error("Return type is not an array: AutoSizeResult")

				if ( returns.nativeType is StructType && !returns.has(StructBuffer) )
					it.error("Return type must be annotated with StructBuffer: AutoSizeResult")
			}

			if ( it has AutoType ) {
				val bufferParamName = it[AutoType].reference
				val bufferParam = paramMap[bufferParamName]
				if ( bufferParam == null )
					it.error("Buffer reference does not exist: AutoType($bufferParamName)")
				else when {
					bufferParam.nativeType !is PointerType                -> it.error("Buffer reference must be a pointer type: AutoType($bufferParamName)")
					bufferParam.nativeType.mapping != PointerMapping.DATA -> it.error("Pointer reference must have a DATA mapping: AutoType($bufferParamName)")
				}
			}

			if ( it has Return ) {
				returnCount++
				if ( 1 < returnCount )
					it.error("More than one return value found.")

				val returnMod = it[Return]

				if ( returnMod === ReturnParam ) {
					if ( !returns.isVoid )
						it.error("The ReturnParam modifier can only be used in functions with void return type.")
				} else {
					if ( returnMod.lengthParam.startsWith(RESULT) ) {
						if ( !returns.nativeType.mapping.let({ it === PrimitiveMapping.INT || it === PrimitiveMapping.POINTER }) )
							it.error("The Return modifier was used in a function with an unsupported return type")

						if ( !it.has(Check) )
							it.error("A Check for ReturnParam parameter does not exist")

						if ( hasAutoSizeFor(it) )
							it.error("Invalid combination of AutoSize and ReturnParam modifiers in function with non-void return type")
					} else {
						if ( !returns.isVoid )
							it.error("The Return modifier was used in a function with an unsupported return type")

						if ( !hasAutoSizeFor(it) )
							it.error("An AutoSize for Return parameter does not exist")

						val lengthParam = paramMap[returnMod.lengthParam]
						if ( lengthParam == null )
							it.error("The length parameter does not exist: Return(${returnMod.lengthParam})")
						else if ( !lengthParam.nativeType.mapping.isPointerSize )
							it.error("The length parameter must be an integer pointer type: Return(${returnMod.lengthParam})")
					}
				}
			}

			if ( it has PointerArray ) {
				if ( !hasAutoSizeFor(it) )
					it.error("An AutoSize for PointerArray parameter does not exist")

				val lengthsParamName = it[PointerArray].lengthsParam
				val lengthsParam = paramMap.getRaw(lengthsParamName)
				if ( lengthsParam != null && !lengthsParam.nativeType.mapping.isPointerSize )
					it.error("Lengths reference must be an integer pointer type: PointerArray($lengthsParamName)")
			}
		}
	}

	private fun PrintWriter.generateChecks(mode: GenerationMode, transforms: Map<QualifiedType, FunctionTransform<out QualifiedType>>? = null) {
		val checks = ArrayList<String>()

		// Validate function address
		if ( (has(DependsOn) || has(IgnoreMissing) || (nativeClass.binding?.shouldCheckFunctionAddress(this@NativeClassFunction) ?: false)) && !hasUnsafeMethod )
			checks.add("checkFunctionAddress($FUNCTION_ADDRESS);")

		// We convert multi-byte-per-element buffers to ByteBuffer for NORMAL generation.
		// So we need to scale the length check by the number of bytes per element.
		fun bufferShift(expression: String, param: String, shift: String, transform: FunctionTransform<out QualifiedType>?): String {
			val nativeType = paramMap[param]!!.nativeType
			val mapping =
				if ( transform != null && transform is AutoTypeTargetTransform ) {
					transform.autoType
				} else
					nativeType.mapping as PointerMapping

			if ( !mapping.isMultiByte )
				return expression

			val builder = StringBuilder(expression.length + 8)

			if ( expression.indexOf(' ') != -1 ) {
				builder.append('(')
				builder.append(expression)
				builder.append(')')
			} else
				builder.append(expression)

			builder.append(' ')
			builder.append(shift)
			builder.append(' ')
			builder.append(mapping.byteShift)

			return builder.toString()
		}

		parameters.forEach {
			var prefix = if ( it has Nullable && it.nativeType.mapping != PointerMapping.OPAQUE_POINTER ) "if ( ${it.name} != null ) " else ""

			if ( it.nativeType.mapping === PointerMapping.OPAQUE_POINTER && !it.has(nullable) && !hasUnsafeMethod && it.nativeType !is ObjectType )
				checks.add("checkPointer(${it.name});")

			if ( mode === NORMAL && it.nativeType is CharSequenceType && it.paramType === IN ) {
				if ( it.nativeType.nullTerminated && getReferenceParam(AutoSize, it.name) == null )
					checks.add("${prefix}checkNT${it.nativeType.charMapping.bytes}(${it.name});")
			}

			if ( it.paramType === IN && it has Terminated ) {
				val postfix = if ( mode !== NORMAL && it.nativeType.javaMethodType !== ByteBuffer::class.java ) "" else
					when ( it.nativeType.mapping ) {
						PointerMapping.DATA_SHORT   -> "2"
						PointerMapping.DATA_INT,
						PointerMapping.DATA_FLOAT   -> "4"
						PointerMapping.DATA_LONG,
						PointerMapping.DATA_DOUBLE  -> "8"
						PointerMapping.DATA_POINTER -> "P"
						else                        -> "1"
					}
				checks.add("${prefix}checkNT$postfix(${it.name}${it[Terminated].let { if ( it === NullTerminated ) "" else ", ${it.value}" }});")
			}

			if ( it has Check ) {
				val transform = transforms?.get(it)
				if ( transform !is SkipCheckFunctionTransform ) {
					val check = it[Check]

					if ( check.debug ) prefix = "if ( LWJGLUtil.DEBUG )\n\t\t\t\t$prefix"

					if ( it.nativeType.javaMethodType === ByteBuffer::class.java )
						checks.add("${prefix}checkBuffer(${it.name}, ${bufferShift(check.expression, it.name, ">>", transform)});")
					else if ( mode === NORMAL || it.nativeType is StructType )
						checks.add("${prefix}checkBuffer(${it.name}, ${bufferShift(check.expression, it.name, "<<", transform)});")
					else
						checks.add("${prefix}checkBuffer(${it.name}, ${check.expression});")
				}
			}

			if ( it has AutoSize ) {
				val autoSize = it[AutoSize]
				if ( mode === NORMAL || it.paramType === ParameterType.INOUT ) {
					var expression = it.name
					if ( it.paramType === ParameterType.INOUT ) {
						if ( mode !== NORMAL )
							expression += ".get($expression.position())"
						else if ( it.nativeType.mapping === PointerMapping.DATA_INT )
							expression += ".getInt($expression.position())"
						else
							expression = "PointerBuffer.get($expression, $expression.position())"
					}
					if ( autoSize.factor != null )
						expression += " ${autoSize.factor.expressionInv()}"

					sequenceOf(autoSize.reference, *autoSize.dependent).forEach {
						prefix = if ( paramMap[it]!! has Nullable ) "if ( $it != null ) " else ""
						checks.add("${prefix}checkBuffer($it, ${if ( mode === NORMAL ) bufferShift(expression, it, "<<", null) else expression});")
					}
				}

				if ( mode !== NORMAL ) {
					val referenceTransform = transforms!![paramMap[autoSize.reference]!!]
					val expression =
						if ( referenceTransform != null && (referenceTransform.javaClass === SingleValueTransform::class.java || referenceTransform === PointerArrayTransformSingle) )
							"1"
						else if ( referenceTransform != null && referenceTransform != PointerArrayTransformSingle )
							"${autoSize.reference}.length"
						else
							"${autoSize.reference}.remaining()"

					autoSize.dependent.forEach {
						val param = paramMap[it]!!
						val transform = transforms[param]
						if ( transform !is SkipCheckFunctionTransform ) {
							prefix = if ( param has Nullable && transform !is PointerArrayTransform ) "if ( $it != null ) " else ""
							checks.add(if ( transform === PointerArrayTransformArray )
								"${prefix}checkArray($it, $expression);"
							else
								"${prefix}checkBuffer($it, $expression);")
						}
					}
				}
			}

			nativeClass.binding?.addParameterChecks(checks, mode, it) { transforms?.get(this) === it }
		}

		if ( checks.isEmpty() )
			return

		println("\t\tif ( LWJGLUtil.CHECKS )${if ( checks.size == 1 ) "" else " {" }")
		checks.forEach {
			print("\t\t\t")
			println(it)
		}
		if ( 1 < checks.size )
			println("\t\t}")
	}

	/** This is where we start generating java code. */
	fun generateMethods(writer: PrintWriter) {
		val simpleFunction = isSimpleFunction

		if ( hasCustomJNI )
			writer.generateNativeMethod(simpleFunction)

		if ( !simpleFunction ) {
			if ( hasUnsafeMethod )
				writer.generateUnsafeMethod()

			// This the only special case where we don't generate a "normal" Java method. If we did,
			// we'd need to add a postfix to either this or the alternative method, since we're
			// changing the return type. It looks ugly and LWJGL didn't do it pre-3.0 either.
			if ( returns.nativeType !is CharSequenceType )
				writer.generateJavaMethod()

			writer.generateAlternativeMethods()
		}
	}

	// --[ JAVA METHODS ]--

	private fun PrintWriter.generateJavaDocLink(description: String, function: NativeClassFunction) {
		println("\t/** $description ${function.nativeClass.processDocumentation(function.methodLink)} */")
	}

	private fun PrintWriter.generateNativeMethod(nativeOnly: Boolean) {
		if ( nativeOnly ) {
			if ( documentation.isNotEmpty() )
				println(documentation)
		} else {
			generateJavaDocLink("JNI method for", this@NativeClassFunction)
			println("\t@JavadocExclude")
		}
		print("\t${accessModifier}static native $returnsNativeMethodType ")
		if ( !nativeOnly ) print('n')
		print(name)
		print("(")

		val nativeParams = getNativeParams()
		if ( nativeClass.binding != null ) {
			print("long $FUNCTION_ADDRESS")
			if ( nativeParams.any() ) print(", ")
		}
		printList(nativeParams) {
			it.asNativeMethodParam
		}
		if ( returnsStructValue ) {
			if ( nativeClass.binding != null || nativeParams.any() ) print(", ")
			print("long $RESULT")
		}

		println(");\n")
	}

	private fun PrintWriter.generateUnsafeMethod() {
		generateJavaDocLink("Unsafe version of", this@NativeClassFunction)
		println("\t@JavadocExclude")
		print("\t${accessModifier}static $returnsNativeMethodType n$name(")
		printList(getNativeParams()) {
			it.asNativeMethodParam
		}

		if ( returnsStructValue ) {
			if ( this@NativeClassFunction.hasNativeParams ) print(", ")
			print("long $RESULT")
		}
		println(") {")

		// Get function address
		nativeClass.binding!!.generateFunctionAddress(this, this@NativeClassFunction)

		// Basic checks
		val checks = ArrayList<String>(4)
		if ( has(DependsOn) || has(IgnoreMissing) || nativeClass.binding.shouldCheckFunctionAddress(this@NativeClassFunction) )
			checks.add("checkFunctionAddress($FUNCTION_ADDRESS);")
		parameters.forEach {
			if ( it.nativeType.mapping === PointerMapping.OPAQUE_POINTER && !it.has(nullable) && it.nativeType !is ObjectType )
				checks.add("checkPointer(${it.name});")
		}

		if ( checks.isNotEmpty() ) {
			println("\t\tif ( LWJGLUtil.CHECKS )${if ( checks.size == 1) "" else " {" }")
			checks.forEach {
				print("\t\t\t")
				println(it)
			}
			if ( 1 < checks.size )
				println("\t\t}")
		}

		// Native method call
		print("\t\t")
		if ( !returns.isVoid )
			print("return ")

		print("${nativeClass.binding.callingConvention.method}${getNativeParams().map { it.nativeType.mapping.jniSignature }.joinToString("")}${returns.nativeType.mapping.jniSignature}(")
		print("$FUNCTION_ADDRESS")
		if ( hasNativeParams ) print(", ")
		printList(getNativeParams()) {
			it.name
		}
		println(");")

		println("\t}\n")
	}

	private fun PrintWriter.generateJavaMethod() {
		// Step 0: JavaDoc

		if ( !(nativeClass.binding?.printCustomJavadoc(this, this@NativeClassFunction, documentation) ?: false) && documentation.isNotEmpty() )
			println(documentation)

		// Step 1: Method signature

		print("\t${accessModifier}static $returnsJavaMethodType $name(")
		printList(parameters.asSequence()) {
			if ( it.isAutoSizeResultOut && hideAutoSizeResultParam )
				null
			else if ( it.isBufferPointer && it.nativeType !is StructType)
				"ByteBuffer ${it.name}" // Convert multi-byte-per-element buffers to ByteBuffer
			else
				it.asJavaMethodParam
		}

		if ( returnsStructValue ) {
			if ( !parameters.isEmpty() ) print(", ")
			print("${(returns.nativeType as StructType).definition.className} $RESULT")
		}

		println(") {")

		val code = if ( this@NativeClassFunction.has(Code) ) this@NativeClassFunction[Code] else Code.NO_CODE

		// Step 2: Get function address

		if ( nativeClass.binding != null && !hasUnsafeMethod )
			nativeClass.binding.generateFunctionAddress(this, this@NativeClassFunction)

		// Step 3.a: Generate checks
		printCode(code.javaInit, ApplyTo.NORMAL)
		generateChecks(NORMAL);

		// Step 3.b: Prepare APIBuffer parameters.

		if ( hideAutoSizeResultParam ) {
			println("\t\tAPIBuffer $API_BUFFER = apiBuffer();")

			val autoSizeParam = getParam { it has AutoSizeResult }
			val autoSizeType = if ( autoSizeParam.nativeType.mapping === PointerMapping.DATA_INT ) "int" else "long"
			println("\t\tint ${autoSizeParam.name} = $API_BUFFER.${autoSizeType}Param();")
		}

		// Step 4: Call the native method
		generateCodeBeforeNative(code, ApplyTo.NORMAL)

		if ( hasCustomJNI || !returns.has(Address) ) {
			generateNativeMethodCall(code.hasStatements(code.javaAfterNative, ApplyTo.NORMAL)) {
				printList(getNativeParams()) {
					it.asNativeMethodCallParam(this@NativeClassFunction, NORMAL)
				}

				if ( returnsStructValue ) {
					if ( hasNativeParams ) print(", ")
					print("$RESULT.$ADDRESS")
				}
			}
		}

		generateCodeAfterNative(code, ApplyTo.NORMAL)

		if ( !(returns.isVoid || returnsStructValue) ) {
			if ( returns.isBufferPointer ) {
				print("\t\t")
				if ( returns.nativeType is StructType ) {
					if ( returns has StructBuffer ) {
						val autoSizeParam = getParam { it has AutoSizeResult }
						println("return new ${returns.nativeType.definition.className}.Buffer(memByteBuffer($RESULT, $API_BUFFER.intValue(${autoSizeParam.name}) * ${returns.nativeType.definition.className}.SIZEOF));")
					} else {
						println("return new ${returns.nativeType.definition.className}($RESULT);")
					}
				} else {
					val isNullTerminated = returns.nativeType is CharSequenceType && returns.nativeType.nullTerminated
					val bufferType = if ( isNullTerminated || returns.nativeType.mapping === PointerMapping.DATA )
						"ByteBuffer"
					else
						returns.nativeType.mapping.javaMethodType.simpleName

					print("return mem$bufferType")
					if ( isNullTerminated )
						print("NT${(returns.nativeType as CharSequenceType).charMapping.bytes}")
					print("($RESULT")
					if ( returns has MapPointer )
						print(", ${if ( paramMap[returns[MapPointer].sizeExpression]?.nativeType?.mapping == PrimitiveMapping.POINTER ) "(int)" else ""}${returns[MapPointer].sizeExpression}")
					else if ( !isNullTerminated ) {
						if ( hasParam { it has AutoSizeResult } ) {
							val params = getParams { it has AutoSizeResult }
							val single = params.count() == 1
							print(", ${params.map {
								if ( it.paramType === IN ) {
									if ( it.nativeType.mapping === PrimitiveMapping.INT )
										"${it.name}"
									else
										"(int)${it.name}"
								} else if ( it.nativeType.mapping === PointerMapping.DATA_INT ) {
									if ( single )
										"$API_BUFFER.intValue(${it.name})"
									else
										"${it.name}.getInt(${it.name}.position())"
								} else {
									if ( single )
										"(int)$API_BUFFER.longValue(${it.name})"
									else
										"(int)${it.name}.getLong(${it.name}.position())"
								}
							}.joinToString(" * ")}")
						} else if ( returns has Address )
							print(", 1")
						else
							throw IllegalStateException("No AutoSizeResult parameter could be found.")
					}
					println(");")
				}
			} else if ( code.hasStatements(code.javaAfterNative, ApplyTo.NORMAL) )
				println("\t\treturn $RESULT;")
		}

		println("\t}\n")
	}

	private fun PrintWriter.printCode(statements: List<Code.Statement>, applyTo: ApplyTo) {
		if ( statements.isEmpty() )
			return

		statements.filter { it.applyTo === ApplyTo.BOTH || it.applyTo === applyTo }.forEach {
			println(it.code)
		}
	}

	private fun PrintWriter.generateCodeBeforeNative(code: Code, applyTo: ApplyTo) {
		printCode(code.javaBeforeNative, applyTo)

		if ( code.hasStatements(code.javaFinally, applyTo) ) {
			println("\t\ttry {")
			print('\t')
		}
	}

	private fun PrintWriter.generateCodeAfterNative(code: Code, applyTo: ApplyTo) {
		val finally = code.getStatements(code.javaFinally, applyTo)
		if ( finally.isNotEmpty() )
			print('\t')
		printCode(code.javaAfterNative, applyTo)

		if ( finally.isNotEmpty() ) {
			println("\t} finally {")
			finally.forEach {
				println(it.code)
			}
			println("\t\t}")
		}
	}

	private fun PrintWriter.generateNativeMethodCall(
		returnLater: Boolean,
		printParams: PrintWriter.() -> Unit
	) {
		val hasConstructor = returns.nativeType is ObjectType
		val returnType = if ( hasConstructor ) (returns.nativeType as ObjectType).className else returnsNativeMethodType

		print("\t\t")
		if ( !(returns.isVoid || returnsStructValue) ) {
			if ( returnLater || returns.isBufferPointer ) {
				print("$returnType $RESULT = ")
				if ( hasConstructor )
					print("$returnType.create(")
			} else {
				print("return ")
				if ( hasConstructor )
					print("$returnType.create(")
			}
		}

		if ( hasUnsafeMethod ) {
			print("n$name(")
		} else {
			print(
				if ( hasCustomJNI )
					"n$name("
				else
					"${nativeClass.binding!!.callingConvention.method}${getNativeParams().map { it.nativeType.mapping.jniSignature }.joinToString("")}${returns.nativeType.mapping.jniSignature}("
			)
			if ( nativeClass.binding != null ) {
				print("$FUNCTION_ADDRESS")
				if ( hasNativeParams ) print(", ")
			}
		}
		printParams()
		print(")")

		if ( hasConstructor ) {
			if ( returns has Construct ) {
				val construct = returns[Construct]
				print(", ${construct.firstArg}")
				for (arg in construct.otherArgs)
					print(", $arg")
			}
			print(")")
		}
		println(";")
	}

	/** Alternative methods are generated by applying one or more transformations. */
	private fun PrintWriter.generateAlternativeMethods() {
		val transforms = LinkedHashMap<QualifiedType, FunctionTransform<out QualifiedType>>()

		nativeClass.binding?.generateAlternativeMethods(this, this@NativeClassFunction, transforms)

		if ( returns.nativeType is CharSequenceType )
			transforms[returns] = StringReturnTransform
		else if ( returns has MapPointer ) {
			val mapPointer = returns[MapPointer]

			transforms[returns] = if ( paramMap.containsKey(mapPointer.sizeExpression) )
				MapPointerExplicitTransform(lengthParam = mapPointer.sizeExpression, addParam = false)
			else
				MapPointerTransform
		}

		// Step 1: Apply basic transformations
		parameters.forEach {
			if ( it.paramType === IN ) {
				if ( it has AutoSize ) {
					val autoSize = it[AutoSize]
					val param = paramMap[autoSize.reference]!! // TODO: Check dependent too?
					// Check if there's also an optional on the referenced parameter. Skip if so.
					if ( !(param has optional) )
						transforms[it] = AutoSizeTransform(param, autoSize.applyTo)
				} else if ( it has optional ) {
					transforms[it] = ExpressionTransform("0L")
				} else if ( it has Expression ) {
					// We do this here in case another transform applies too.
					// We overwrite the value with the expression but use the type of the other transform.
					val expression = it[Expression]
					transforms[it] = ExpressionTransform(expression.value, expression.keepParam, @Suppress("UNCHECKED_CAST")(transforms[it] as FunctionTransform<Parameter>?))
				}
			}
		}

		// Step 2: Check if we have any basic transformation to apply or if we have a multi-byte-per-element buffer parameter
		if ( !transforms.isEmpty() || parameters.any { it.isBufferPointer && (it.nativeType.mapping as PointerMapping).isMultiByte && !(it.isAutoSizeResultOut && hideAutoSizeResultParam) } )
			generateAlternativeMethod(name, "Alternative version of:", transforms)

		// Step 3: Generate more complex alternatives if necessary
		if ( returns has MapPointer ) {
			// The size expression may be an existing parameter, in which case we don't need an explicit size alternative.
			if ( !paramMap.containsKey(returns[MapPointer].sizeExpression) ) {
				transforms[returns] = MapPointerExplicitTransform("length")
				generateAlternativeMethod(name, "Explicit size alternative version of:", transforms)
			}
		}

		// Apply any CharSequenceTransforms. These can be combined with any of the other transformations.
		if ( parameters.count {
			if ( it.paramType === OUT || it.nativeType !is CharSequenceType )
				false
			else {
				val hasAutoSize = hasAutoSizeFor(it)
				it.apply {
					if ( hasAutoSize )
						getParams(hasAutoSizePredicate(this)).forEach { transforms[it] = AutoSizeCharSequenceTransform(this) }
				}
				transforms[it] = CharSequenceTransform(!hasAutoSize)
				true
			}
		} != 0 )
			generateAlternativeMethod(name, "CharSequence version of:", transforms)

		fun applyReturnValueTransforms(param: Parameter) {
			// Transform void to the proper type
			transforms[returns] = PrimitiveValueReturnTransform(PointerMapping.primitiveMap[param.nativeType.mapping as PointerMapping]!!, param.name)

			// Transform the AutoSize parameter, if there is one
			getParams(hasAutoSizePredicate(param)).forEach {
				transforms[it] = Expression1Transform
			}

			// Transform the returnValue parameter
			transforms[param] = PrimitiveValueTransform
		}

		// Apply any complex transformations.
		parameters.forEach {
			if ( it has Return && !hasParam { it has PointerArray } ) {
				val returnMod = it[Return]

				if ( returnMod === ReturnParam && it.nativeType !is CharSequenceType ) {
					if ( !hasParam { it has SingleValue || it has PointerArray } ) {
						// Skip, we inject the Return alternative in these transforms
						applyReturnValueTransforms(it)
						generateAlternativeMethod(stripPostfix(name, stripType = false), "Single return value version of:", transforms)
					}
				} else if ( returnMod.lengthParam.startsWith(RESULT) ) {
					transforms[returns] = BufferAutoSizeReturnTransform(it, returnMod.lengthParam)
					transforms[it] = BufferReplaceReturnTransform
					generateAlternativeMethod(name, "Buffer return version of:", transforms)
				} else {
					// Remove any transform from the maxLength parameter
					val maxLengthParam = getParam(hasAutoSizePredicate(it))
					transforms.remove(maxLengthParam)

					// Hide length parameter and use APIBuffer
					val lengthParam = returnMod.lengthParam
					if ( lengthParam.isNotEmpty() )
						transforms[paramMap[lengthParam]!!] = BufferLengthTransform

					// Hide target parameter and use APIBuffer
					transforms[it] = BufferAutoSizeTransform

					// Transform void to the buffer type
					val returnType: String
					if ( it.nativeType is CharSequenceType ) {
						transforms[returns] = if ( lengthParam.isNotEmpty() )
							BufferReturnTransform(it, lengthParam, it.nativeType.charMapping.charset)
						else
							BufferReturnNTTransform(
								it,
								if ( 4 < (maxLengthParam.nativeType.mapping as PrimitiveMapping).bytes ) "(int)${maxLengthParam.name}" else maxLengthParam.name,
								it.nativeType.charMapping.charset
							)
						returnType = "String"
					} else {
						transforms[returns] = BufferReturnTransform(it, lengthParam)
						returnType = "Buffer"
					}

					generateAlternativeMethod(name, "$returnType return version of:", transforms)

					if ( returnMod.maxLengthExpression != null ) {
						// Transform maxLength parameter and generate an additional alternative
						transforms[maxLengthParam] = ExpressionLocalTransform(returnMod.maxLengthExpression)
						generateAlternativeMethod(name, "$returnType return (w/ implicit max length) version of:", transforms)
					}
				}
			} else if ( it has MultiType ) {
				// Generate MultiType alternatives

				// Add the AutoSize transformation if we skipped it above
				getParams { it has AutoSize }.forEach {
					val autoSize = it[AutoSize]
					transforms[it] = AutoSizeTransform(paramMap[autoSize.reference]!!, autoSize.applyTo)
				}

				var multiTypes = it[MultiType].types.asSequence()
				if ( it has optional )
					multiTypes = sequenceOf(PointerMapping.DATA_BYTE) + multiTypes

				for (autoType in multiTypes) {
					// Transform the AutoSize parameter, if there is one
					getReferenceParam(AutoSize, it.name).let { autoSizeParam ->
						if ( autoSizeParam != null )
							transforms[autoSizeParam] = AutoSizeTransform(it, ApplyTo.ALTERNATIVE, autoType.byteShift!!)
					}

					transforms[it] = AutoTypeTargetTransform(autoType)
					generateAlternativeMethod(name, "${autoType.javaMethodType.simpleName} version of:", transforms)
				}

				getReferenceParam(AutoSize, it.name).let {
					if ( it != null )
						transforms.remove(it)
				}

				// Generate a SingleValue alternative for each type
				if ( it has SingleValue ) {
					val autoSizeParam = getParam(hasAutoSizePredicate(it)) // required

					val singleValue = it[SingleValue]
					for (autoType in multiTypes) {
						// Generate type1, type2, type3, type4 versions
						// TODO: Make customizable? New modifier?
						for (i in 1..4) {
							// Transform the AutoSize parameter
							transforms[autoSizeParam] = ExpressionTransform("(1 << ${autoType.byteShift}) * $i")

							val primitiveType = PointerMapping.primitiveMap[autoType]!!
							transforms[it] = VectorValueTransform(if ( primitiveType == "pointer" ) "long" else primitiveType, primitiveType, singleValue.newName, i)
							generateAlternativeMethod("$name$i${primitiveType[0]}", "${if ( i == 1 ) "Single $primitiveType" else "$primitiveType$i" } value version of:", transforms)
						}
					}

					transforms.remove(autoSizeParam)
				}

				transforms.remove(it)
			} else if ( it has AutoType ) {
				// Generate AutoType alternatives

				val autoTypes = it[AutoType]
				val bufferParam = paramMap[autoTypes.reference]!!

				// Disable AutoSize factor
				val autoSizeParam = getReferenceParam(AutoSize, bufferParam.name)
				if ( autoSizeParam != null )
					transforms[autoSizeParam] = AutoSizeTransform(bufferParam, autoSizeParam[AutoSize].applyTo, applyFactor = false)

				val types = ArrayList<AutoTypeToken>(autoTypes.types.size)
				autoTypes.types.forEach { types.add(it) }

				for (autoType in types) {
					transforms[it] = AutoTypeParamTransform("${autoType.className}.${autoType.name}")
					transforms[bufferParam] = AutoTypeTargetTransform(autoType.mapping)
					generateAlternativeMethod(name, "${autoType.name} version of:", transforms)
				}

				transforms.remove(bufferParam)
				transforms.remove(it)
			}
		}

		// Apply any PointerArray transformations.
		parameters.filter { it has PointerArray }.let {
			if ( it.isEmpty() )
				return@let

			fun Parameter.getAutoSizeReference(): Parameter? = getParams {
				it has AutoSize && it[AutoSize].reference == this.name
			}.firstOrNull()

			// Array version
			it.forEach {
				val pointerArray = it[PointerArray]

				val lengthsParam = paramMap.getRaw(pointerArray.lengthsParam)
				if ( lengthsParam != null )
					transforms[lengthsParam] = PointerArrayLengthsTransform(it, true)

				val countParam = it.getAutoSizeReference()
				if ( countParam != null )
					transforms[countParam] = ExpressionTransform("${it.name}.length")

				transforms[it] = if ( it === parameters.last() {
					it !== lengthsParam && it !== countParam // these will be hidden, ignore
				} ) PointerArrayTransformVararg else PointerArrayTransformArray
			}
			generateAlternativeMethod(name, "Array version of:", transforms)

			// Combine PointerArrayTransformSingle with BufferValueReturnTransform
			getParams { it has ReturnParam }.forEach { applyReturnValueTransforms(it) }

			// Single value version
			val names = it.map {
				val pointerArray = it[PointerArray]

				val lengthsParam = paramMap.getRaw(pointerArray.lengthsParam)
				if ( lengthsParam != null )
					transforms[lengthsParam] = PointerArrayLengthsTransform(it, false)

				val countParam = it.getAutoSizeReference()
				if ( countParam != null )
					transforms[countParam] = ExpressionTransform("1")

				transforms[it] = PointerArrayTransformSingle

				pointerArray.singleName
			}.joinToString(" &amp; ")
			generateAlternativeMethod(name, "Single $names version of:", transforms)

			// Cleanup
			it.forEach {
				val countParam = it.getAutoSizeReference()
				if ( countParam != null )
					transforms.remove(countParam)
				transforms.remove(it)
			}
		}

		// Apply any SingleValue transformations.
		if ( parameters.count {
			if ( !it.has(SingleValue) || it.has(MultiType) ) {
				false
			} else {
				// Compine SingleValueTransform with BufferValueReturnTransform
				getParams { it has ReturnParam }.forEach { applyReturnValueTransforms(it) }

				// Transform the AutoSize parameter, if there is one
				getParams(hasAutoSizePredicate(it)).forEach {
					transforms[it] = Expression1Transform
				}

				val singleValue = it[SingleValue]
				val pointerType = it.nativeType as PointerType
				val primitiveType = PointerMapping.primitiveMap[pointerType.mapping as PointerMapping]!!
				transforms[it] = SingleValueTransform(
					when ( pointerType.elementType ) {
						null                -> primitiveType
						is CharSequenceType -> "CharSequence"
						is ObjectType       -> pointerType.elementType.className
						is PointerType      -> "long"
						else                -> pointerType.elementType.javaMethodType.simpleName
					},
					primitiveType,
					singleValue.newName
				)

				true
			}
		} != 0 )
			generateAlternativeMethod(stripPostfix(name, stripType = false), "Single value version of:", transforms)
	}

	fun PrintWriter.generateAlternativeMethod(
		name: String,
		description: String,
		transforms: Map<QualifiedType, FunctionTransform<out QualifiedType>>
	) {
		val returnTransform = transforms[returns]

		// JavaDoc

		if ( returnTransform === StringReturnTransform ) {
			// Special-case, we skipped the normal method
			if ( documentation.isNotEmpty() )
				println(documentation)
		} else
			generateJavaDocLink(description, this@NativeClassFunction)

		// Method signature

		val retType = returns.transformDeclarationOrElse(transforms, returnsJavaMethodType)

		print("\t${accessModifier}static $retType $name(")
		printList(parameters.asSequence()) {
			if ( it.isAutoSizeResultOut && hideAutoSizeResultParam )
				null
			else
				it.transformDeclarationOrElse(transforms, it.asJavaMethodParam)
		}
		if ( returnTransform === MapPointerTransform ) {
			if ( !parameters.isEmpty() )
				print(", ")
			print("ByteBuffer $MAP_OLD")
		} else if ( returnTransform != null && returnTransform.javaClass === MapPointerExplicitTransform::class.java ) {
			if ( !parameters.isEmpty() )
				print(", ")
			val mapPointerExplicit = returnTransform as MapPointerExplicitTransform
			if ( mapPointerExplicit.addParam )
				print("long ${mapPointerExplicit.lengthParam}, ")
			print("ByteBuffer $MAP_OLD")
		}
		println(") {")

		// Append CodeFunctionTransform statements to Code

		val code = transforms
			.asSequence()
			.filter { it.value is CodeFunctionTransform<*> }
			.fold(if ( has(Code) ) get(Code) else Code.NO_CODE) { code, entry ->
				val (qtype, transform) = entry
				@Suppress("UNCHECKED_CAST")
				(transform as CodeFunctionTransform<QualifiedType>).generate(qtype, code)
			}

		// Get function address

		if ( nativeClass.binding != null && !hasUnsafeMethod )
			nativeClass.binding.generateFunctionAddress(this, this@NativeClassFunction)

		// Generate checks

		printCode(code.javaInit, ApplyTo.ALTERNATIVE)
		generateChecks(ALTERNATIVE, transforms);

		// Prepare APIBuffer parameters

		var apiBufferSet = hideAutoSizeResultParam
		if ( apiBufferSet ) {
			println("\t\tAPIBuffer $API_BUFFER = apiBuffer();")

			val autoSizeParam = getParam { it has AutoSizeResult }
			val autoSizeType = if ( autoSizeParam.nativeType.mapping === PointerMapping.DATA_INT ) "int" else "long"
			println("\t\tint ${autoSizeParam.name} = $API_BUFFER.${autoSizeType}Param();")
		}
		for ((qtype, transform) in transforms) {
			if ( transform is APIBufferFunctionTransform<*> ) {
				if ( !apiBufferSet ) {
					println("\t\tAPIBuffer $API_BUFFER = apiBuffer();")
					apiBufferSet = true
				}

				@Suppress("UNCHECKED_CAST")
				when ( qtype ) {
					is Parameter   -> (transform as APIBufferFunctionTransform<Parameter>).setupAPIBuffer(this@NativeClassFunction, qtype, this)
					is ReturnValue -> (transform as APIBufferFunctionTransform<ReturnValue>).setupAPIBuffer(this@NativeClassFunction, qtype, this)
				}
			}
		}

		// Call the native method

		generateCodeBeforeNative(code, ApplyTo.ALTERNATIVE)

		val returnLater = code.hasStatements(code.javaAfterNative, ApplyTo.ALTERNATIVE) || transforms[returns] is BufferAutoSizeReturnTransform
		generateNativeMethodCall(returnLater) {
			printList(getNativeParams()) {
				it.transformCallOrElse(transforms, it.asNativeMethodCallParam(this@NativeClassFunction, ALTERNATIVE))
			}
		}

		generateCodeAfterNative(code, ApplyTo.ALTERNATIVE)

		// Return

		if ( returns.isVoid || returnsStructValue ) {
			val result = returns.transformCallOrElse(transforms, "")
			if ( !result.isEmpty() )
				println(result)
		} else {
			if ( returns.isBufferPointer ) {
				print("\t\t")
				if ( returns.nativeType is StructType ) {
					if ( returns has StructBuffer ) {
						val autoSizeParam = getParam { it has AutoSizeResult }
						println("return new ${returns.nativeType.definition.className}.Buffer(memByteBuffer($RESULT, ${autoSizeParam.name}.get(${autoSizeParam.name}.position()) * ${returns.nativeType.definition.className}.SIZEOF));")
					} else {
						println("return new ${returns.nativeType.definition.className}($RESULT);")
					}
				} else {
					val isNullTerminated = returns.nativeType is CharSequenceType && returns.nativeType.nullTerminated
					val bufferType = if ( isNullTerminated || returns.nativeType.mapping === PointerMapping.DATA )
						"ByteBuffer"
					else
						returns.nativeType.mapping.javaMethodType.simpleName

					val builder = StringBuilder()
					builder.append("mem$bufferType")
					if ( isNullTerminated )
						builder.append("NT${(returns.nativeType as CharSequenceType).charMapping.bytes}")
					builder.append("($RESULT")
					if ( returns has MapPointer )
						builder.append(", ${returns[MapPointer].sizeExpression}")
					else if ( !isNullTerminated ) {
						if ( hasParam { it has AutoSizeResult } ) {
							val params = getParams { it has AutoSizeResult }
							val single = params.count() == 1
							builder.append(", ${params.map {
								if ( it.paramType === IN )
									"(int)${it.name}"
								else if ( it.nativeType.mapping === PointerMapping.DATA_INT ) {
									if ( single )
										"$API_BUFFER.intValue(${it.name})"
									else
										"${it.name}.get(${it.name}.position())"
								} else {
									if ( single )
										"(int)$API_BUFFER.longValue(${it.name})"
									else
										"(int)${it.name}.get(${it.name}.position())"
								}
							}.joinToString(" * ")}")
						} else
							throw IllegalStateException("No AutoSizeResult parameter could be found.")
					}
					builder.append(")")

					val returnExpression = returns.transformCallOrElse(transforms, builder.toString())
					if ( returnExpression.indexOf('\n') == -1 )
						println("return $returnExpression;")
					else // Multiple statements, assumes the transformation includes the return statement.
						println(returnExpression)
				}
			} else if ( returnLater )
				println(returns.transformCallOrElse(transforms, "\t\treturn $RESULT;"))
		}

		println("\t}\n")
	}

	// --[ JNI FUNCTIONS ]--

	fun generateFunctionDefinition(writer: PrintWriter) = writer.generateFunctionDefinitionImpl()
	private fun PrintWriter.generateFunctionDefinitionImpl() {
		print("typedef ${returns.toNativeType} (APIENTRY *${name}PROC) (")
		val nativeParams = getNativeParams()
		if ( nativeParams.any() ) {
			printList(nativeParams) {
				it.toNativeType
			}
		} else
			print("void")
		println(");")
	}

	fun generateFunction(writer: PrintWriter) = writer.generateFunctionImpl()
	private fun PrintWriter.generateFunctionImpl() {
		// Function signature

		print("JNIEXPORT $returnsJniFunctionType JNICALL Java_${nativeClass.nativeFileNameJNI}_")
		if ( !isSimpleFunction )
			print('n')
		print(name.asJNIName)
		print("(")
		print("JNIEnv *$JNIENV, jclass clazz")
		if ( nativeClass.binding != null )
			print(", jlong $FUNCTION_ADDRESS")
		getNativeParams().forEach {
			print(", ${it.asJNIFunctionParam}")
		}
		if ( returnsStructValue )
			print(", jlong $RESULT")
		println(") {")

		// Cast function address to pointer

		if ( nativeClass.binding != null )
			println("\t${name}PROC $name = (${name}PROC)(intptr_t)$FUNCTION_ADDRESS;")

		// Cast addresses to pointers

		getNativeParams().filter { it.nativeType is PointerType }.forEach {
			val pointerType = it.toNativeType
			print("\t$pointerType")
			if ( !pointerType.endsWith('*') ) print(' ')
			println("${it.name} = ($pointerType)(intptr_t)${it.name}$POINTER_POSTFIX;")
		}

		// Custom code

		val code = if ( has(Code) ) this@NativeClassFunction[Code] else null

		if ( code?.nativeAfterCall != null && !returns.isVoid )
			println("\t$returnsJniFunctionType $RESULT;")

		code?.nativeBeforeCall.let { if ( it != null ) println(it) }

		// Unused parameter macro

		println("\tUNUSED_PARAMS($JNIENV, clazz)")

		// Call native function

		code?.nativeCall.let {
			if ( it != null )
				println(it)
			else {
				print('\t')
				if ( returnsStructValue ) {
					print("*((${returns.nativeType.name}*)(intptr_t)$RESULT) = ")
				} else if ( !returns.isVoid ) {
					print(if ( code?.nativeAfterCall != null ) "$RESULT =" else "return")
					print(" ($returnsJniFunctionType)")
					if ( returns.nativeType is PointerType )
						print("(intptr_t)")
					if ( returns.has(Address) )
						print('&')
				}
				print("$nativeName")
				if ( !has(Macro) ) print('(')
				printList(getNativeParams()) {
					// Avoids warning when implicitly casting from jlong to 32-bit pointer.
					if ( it.nativeType.mapping === PrimitiveMapping.POINTER )
						"(${it.nativeType.name})${it.name}"
					else if ( it.nativeType is StructType && !it.nativeType.includesPointer )
						"*${it.name}"
					else
						it.name
				}
				if ( !has(Macro) ) print(')')
				println(';')
			}
		}

		code?.nativeAfterCall.let {
			if ( it == null ) return@let

			println(it)
			if ( !returns.isVoid )
				println("\treturn $RESULT;")
		}

		println("}")
	}

}

// TODO: Remove if KT-457 or KT-1183 are fixed.
fun NativeClassFunction.generateAlternativeMethod(
	writer: PrintWriter, name: String,
	description: String,
	transforms: Map<QualifiedType, FunctionTransform<out QualifiedType>>
) = writer.generateAlternativeMethod(name, description, transforms)