/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.egl.templates

import org.lwjgl.generator.*
import org.lwjgl.egl.*

val KHR_image_pixmap = "KHRImagePixmap".nativeClassEGL("KHR_image_pixmap", postfix = KHR) {
	documentation =
		"""
		Native bindings to the $registryLink extension.

		This extension allows creating an EGLImage from a native pixmap image.

		Requires ${EGL12.core} and ${KHR_image_base.link}.
		"""

	IntConstant(
		"",

		"NATIVE_PIXMAP_KHR"..0x30B0
	)
}