/*******************************************************************************
 * Copyright (c) 2008-2012 The Khronos Group Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and/or associated documentation files (the
 * "Materials"), to deal in the Materials without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Materials, and to
 * permit persons to whom the Materials are furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Materials.
 *
 * THE MATERIALS ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
 * CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * MATERIALS OR THE USE OR OTHER DEALINGS IN THE MATERIALS.
 ******************************************************************************/

/* $Revision: 11708 $ on $Date: 2010-06-13 23:36:24 -0700 (Sun, 13 Jun 2010) $ */

#ifndef __OPENCL_H
#define __OPENCL_H

DISABLE_WARNINGS()

#ifdef __APPLE__
	#include <OpenCL/cl.h>
#else
	#include "CL/cl.h"
#endif

ENABLE_WARNINGS()

// amd_device_topology

typedef union {
    struct { cl_uint type; cl_uint data[5]; } raw;
    struct { cl_uint type; cl_char unused[17]; cl_char bus; cl_char device; cl_char function; } pcie;
} cl_device_topology_amd;

// amd_bus_addressable_memory

typedef struct {
	cl_ulong surface_bus_address;
	cl_ulong marker_bus_address;
} cl_bus_address_amd;

// intel_motion_estimation

typedef struct _cl_motion_estimation_desc_intel {
    cl_uint     mb_block_type;
    cl_uint     subpixel_mode;
    cl_uint     sad_adjust_mode;
    cl_uint     search_path_type;
} cl_motion_estimation_desc_intel;

#endif  /* __OPENCL_H   */
