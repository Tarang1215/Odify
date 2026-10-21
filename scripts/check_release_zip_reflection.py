#!/usr/bin/env python3
"""Reject a minified Odify release whose reflective ZIP field classes vanish.

Android's youtubedl-android first unpacks the bundled Python runtime. Apache
Commons Compress ExtraFieldUtils registers ZIP implementations using reflective
no-argument constructors. R8 may otherwise remove those constructors, causing
ExceptionInInitializerError: class ... is not a concrete class on device.
"""
import argparse
from pathlib import Path
import struct
import zipfile

PACKAGE = "Lorg/apache/commons/compress/archivers/zip/"
REGISTERED = (
    "AsiExtraField",
    "X5455_ExtendedTimestamp",
    "X7875_NewUnix",
    "JarMarker",
    "UnicodePathExtraField",
    "UnicodeCommentExtraField",
    "Zip64ExtendedInformationExtraField",
)


def uleb(data, offset):
    value = 0
    for shift in range(0, 35, 7):
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if byte < 0x80:
            return value, offset
    raise ValueError("Invalid DEX unsigned LEB128")


def descriptors(data):
    if not data.startswith(b"dex\\n"):
        raise ValueError("Invalid DEX header")
    def fields(offset):
        return struct.unpack_from("<II", data, offset)
    strings_count, strings_offset = fields(0x38)
    types_count, types_offset = fields(0x40)
    protos_count, protos_offset = fields(0x48)
    methods_count, methods_offset = fields(0x58)
    classes_count, classes_offset = fields(0x60)
    strings = []
    for i in range(strings_count):
        (at,) = struct.unpack_from("<I", data, strings_offset + i * 4)
        _, at = uleb(data, at)
        end = data.index(b"\\0", at)
        strings.append(data[at:end].decode("utf-8", errors="replace"))
    types = [strings[struct.unpack_from("<I", data, types_offset + i * 4)[0]]
             for i in range(types_count)]
    methods = [struct.unpack_from("<HHI", data, methods_offset + i * 8)
               for i in range(methods_count)]
    protos = [struct.unpack_from("<III", data, protos_offset + i * 12)
              for i in range(protos_count)]
    found = {}
    for i in range(classes_count):
        at = classes_offset + i * 32
        class_idx, access_flags, _, _, _, _, class_data_off, _ = struct.unpack_from(
            "<8I", data, at)
        name = types[class_idx]
        if not name.startswith(PACKAGE):
            continue
        # Class.newInstance() requires an instantiable class and an accessible
        # public, zero-argument constructor. Merely keeping a class name
        # would not have prevented the Galaxy release crash.
        constructors = False
        if class_data_off:
            at = class_data_off
            static_count, at = uleb(data, at)
            instance_count, at = uleb(data, at)
            direct_count, at = uleb(data, at)
            _, at = uleb(data, at)  # virtual method count
            for _ in range(static_count + instance_count):
                _, at = uleb(data, at)
                _, at = uleb(data, at)
            method_idx = 0
            for _ in range(direct_count):
                diff, at = uleb(data, at)
                method_idx += diff
                method_flags, at = uleb(data, at)
                code_off, at = uleb(data, at)
                _, proto_idx, method_name_idx = methods[method_idx]
                if (strings[method_name_idx] == "<init>"
                        and protos[proto_idx][2] == 0
                        and method_flags & 0x1 and code_off != 0):
                    constructors = True
        found[name] = ((access_flags & 0x600) == 0, constructors)
    return found


def check(apk: Path):
    classes = {}
    with zipfile.ZipFile(apk) as archive:
        files = sorted(name for name in archive.namelist()
                       if name.startswith("classes") and name.endswith(".dex"))
        if not files:
            raise RuntimeError("No Dalvik classes in APK")
        for file in files:
            classes.update(descriptors(archive.read(file)))
    missing = []
    for name in REGISTERED:
        key = PACKAGE + name + ";"
        concrete, constructor = classes.get(key, (False, False))
        if not (concrete and constructor):
            missing.append(name)
    if missing:
        raise RuntimeError(
            "R8 ZIP reflection protection failed (missing concrete public "
            "zero-argument constructor): " + ", ".join(missing))
    print(f"Verified {len(REGISTERED)} reflective ZIP constructors after R8 "
          f"in {apk.name}; physical Android launch still required.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    check(parser.parse_args().apk)
