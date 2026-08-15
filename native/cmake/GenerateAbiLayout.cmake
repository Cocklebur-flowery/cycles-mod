cmake_minimum_required(VERSION 3.25)

if(NOT DEFINED ABI_SCHEMA_FILE OR NOT EXISTS "${ABI_SCHEMA_FILE}")
    message(FATAL_ERROR "ABI_SCHEMA_FILE must identify an existing schema")
endif()
if(NOT DEFINED ABI_CPP_OUTPUT AND NOT DEFINED ABI_JAVA_OUTPUT)
    message(FATAL_ERROR "At least one ABI output path must be provided")
endif()

file(READ "${ABI_SCHEMA_FILE}" abi_schema)
string(JSON abi_name ERROR_VARIABLE abi_name_error GET "${abi_schema}" name)
string(JSON abi_size ERROR_VARIABLE abi_size_error GET "${abi_schema}" size)
string(JSON abi_field_count ERROR_VARIABLE abi_fields_error LENGTH "${abi_schema}" fields)
if(abi_name_error OR abi_size_error OR abi_fields_error)
    message(FATAL_ERROR "Malformed ABI schema: ${ABI_SCHEMA_FILE}")
endif()
if(abi_field_count LESS 1 OR abi_size LESS 1)
    message(FATAL_ERROR "ABI schema must contain fields and a positive size")
endif()

if(DEFINED ABI_JAVA_OUTPUT)
    string(JSON abi_java_class ERROR_VARIABLE abi_java_class_error
        GET "${abi_schema}" java_class)
    string(JSON abi_java_package ERROR_VARIABLE abi_java_package_error
        GET "${abi_schema}" java_package)
    if(abi_java_class_error OR abi_java_package_error)
        message(FATAL_ERROR "Java ABI output requires java_class and java_package")
    endif()
    set(java_layout_entries "")
    set(java_offset_constants "")
endif()

if(DEFINED ABI_CPP_OUTPUT)
    set(cpp_assertions
        "#pragma once\n\n#include <cstddef>\n\nstatic_assert(sizeof(${abi_name}) == ${abi_size});\n")
endif()

set(cursor 0)
set(seen_field_names "")
math(EXPR abi_field_last "${abi_field_count} - 1")
foreach(field_index RANGE 0 ${abi_field_last})
    string(JSON field_name ERROR_VARIABLE field_name_error
        GET "${abi_schema}" fields ${field_index} name)
    string(JSON field_type ERROR_VARIABLE field_type_error
        GET "${abi_schema}" fields ${field_index} type)
    string(JSON field_offset ERROR_VARIABLE field_offset_error
        GET "${abi_schema}" fields ${field_index} offset)
    if(field_name_error OR field_type_error OR field_offset_error)
        message(FATAL_ERROR "Malformed ABI field at index ${field_index}")
    endif()
    if(field_name IN_LIST seen_field_names)
        message(FATAL_ERROR "Duplicate ABI field name: ${field_name}")
    endif()
    list(APPEND seen_field_names "${field_name}")

    if(field_type STREQUAL "uint32")
        set(field_size 4)
        set(java_layout_type "JAVA_INT")
    elseif(field_type STREQUAL "uint64")
        set(field_size 8)
        set(java_layout_type "JAVA_LONG")
    else()
        message(FATAL_ERROR "Unsupported ABI field type: ${field_type}")
    endif()

    if(field_offset LESS cursor)
        message(FATAL_ERROR "ABI field ${field_name} overlaps or is out of order")
    endif()
    if(DEFINED ABI_JAVA_OUTPUT AND field_offset GREATER cursor)
        math(EXPR field_padding "${field_offset} - ${cursor}")
        string(APPEND java_layout_entries
            "            MemoryLayout.paddingLayout(${field_padding}L),\n")
    endif()

    if(DEFINED ABI_JAVA_OUTPUT)
        string(APPEND java_layout_entries
            "            ${java_layout_type}.withName(\"${field_name}\"),\n")
        string(TOUPPER "${field_name}" field_constant)
        string(APPEND java_offset_constants
            "    static final long ${field_constant}_OFFSET = ${field_offset}L;\n")
    endif()
    if(DEFINED ABI_CPP_OUTPUT)
        string(APPEND cpp_assertions
            "static_assert(offsetof(${abi_name}, ${field_name}) == ${field_offset});\n")
    endif()

    math(EXPR cursor "${field_offset} + ${field_size}")
endforeach()

if(cursor GREATER abi_size)
    message(FATAL_ERROR "ABI fields exceed declared size ${abi_size}")
endif()
if(DEFINED ABI_JAVA_OUTPUT AND cursor LESS abi_size)
    math(EXPR trailing_padding "${abi_size} - ${cursor}")
    string(APPEND java_layout_entries
        "            MemoryLayout.paddingLayout(${trailing_padding}L),\n")
endif()

if(DEFINED ABI_JAVA_OUTPUT)
    string(LENGTH "${java_layout_entries}" java_layout_length)
    math(EXPR java_layout_content_length "${java_layout_length} - 2")
    string(SUBSTRING "${java_layout_entries}" 0 ${java_layout_content_length}
        java_layout_entries)
    string(APPEND java_layout_entries "\n")
    string(CONCAT java_source
        "package ${abi_java_package};\n\n"
        "import java.lang.foreign.MemoryLayout;\n\n"
        "import static java.lang.foreign.ValueLayout.JAVA_INT;\n"
        "import static java.lang.foreign.ValueLayout.JAVA_LONG;\n\n"
        "final class ${abi_java_class} {\n"
        "    static final long BYTE_SIZE = ${abi_size}L;\n"
        "    static final MemoryLayout LAYOUT = MemoryLayout.structLayout(\n"
        "${java_layout_entries}"
        "    );\n\n"
        "${java_offset_constants}\n"
        "    private ${abi_java_class}() {\n"
        "    }\n"
        "}\n")
    get_filename_component(java_output_directory "${ABI_JAVA_OUTPUT}" DIRECTORY)
    file(MAKE_DIRECTORY "${java_output_directory}")
    file(WRITE "${ABI_JAVA_OUTPUT}" "${java_source}")
endif()

if(DEFINED ABI_CPP_OUTPUT)
    get_filename_component(cpp_output_directory "${ABI_CPP_OUTPUT}" DIRECTORY)
    file(MAKE_DIRECTORY "${cpp_output_directory}")
    file(WRITE "${ABI_CPP_OUTPUT}" "${cpp_assertions}")
endif()
