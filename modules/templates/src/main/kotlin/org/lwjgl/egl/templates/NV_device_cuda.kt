/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.egl.templates

import org.lwjgl.generator.*
import org.lwjgl.egl.*

val NV_device_cuda = "NVDeviceCUDA".nativeClassEGL("NV_device_cuda", postfix = NV) {
	documentation =
		"""
		Native bindings to the $registryLink extension.

		EGL and CUDA both have the capability to drive multiple devices, such as GPUs, within a single system. To interoperate with one another, both APIs must
		have compatible notions of such devices. This extension defines a mapping from an EGL device to a CUDA device enumerant.

		Requires ${EXT_device_base.link}.
		"""

	IntConstant(
		"",

		"CUDA_DEVICE_NV"..0x323A
	)
}