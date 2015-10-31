/* 
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.opengl

import org.lwjgl.generator.*

/** Marks an OpenGL function as deprecated; its function address will not be loaded in a forward-compatible context. */
object DeprecatedGL : FunctionModifier() {
	override val isSpecial = false
	override fun validate(func: NativeClassFunction) {
		if ( !func.nativeClass.postfix.isEmpty() )
			throw IllegalArgumentException("The deprecated modifier can only be applied on core functionality.")
	}
}