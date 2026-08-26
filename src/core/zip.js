import { inflateRawSync } from "node:zlib";
import { InputFormatError } from "./errors.js";

const END_OF_CENTRAL_DIRECTORY = 0x06054b50;
const CENTRAL_DIRECTORY_FILE_HEADER = 0x02014b50;
const LOCAL_FILE_HEADER = 0x04034b50;

function findEndOfCentralDirectory(buffer) {
  const lowestOffset = Math.max(0, buffer.length - 0xffff - 22);
  for (let offset = buffer.length - 22; offset >= lowestOffset; offset -= 1) {
    if (buffer.readUInt32LE(offset) === END_OF_CENTRAL_DIRECTORY) return offset;
  }
  throw new InputFormatError("ZIP end-of-central-directory record was not found.");
}

function assertRange(buffer, offset, length, message) {
  if (offset < 0 || length < 0 || offset + length > buffer.length) {
    throw new InputFormatError(message);
  }
}

export function readZipEntries(buffer) {
  if (!Buffer.isBuffer(buffer) || buffer.length < 22) {
    throw new InputFormatError("Resource-pack ZIP is too small.");
  }
  const eocdOffset = findEndOfCentralDirectory(buffer);
  const entryCount = buffer.readUInt16LE(eocdOffset + 10);
  const centralDirectorySize = buffer.readUInt32LE(eocdOffset + 12);
  const centralDirectoryOffset = buffer.readUInt32LE(eocdOffset + 16);

  if (entryCount === 0xffff || centralDirectoryOffset === 0xffffffff) {
    throw new InputFormatError("ZIP64 resource packs are not supported yet.");
  }
  assertRange(buffer, centralDirectoryOffset, centralDirectorySize, "ZIP central directory is outside the file.");

  const entries = new Map();
  let offset = centralDirectoryOffset;
  for (let index = 0; index < entryCount; index += 1) {
    assertRange(buffer, offset, 46, "ZIP central directory entry is truncated.");
    if (buffer.readUInt32LE(offset) !== CENTRAL_DIRECTORY_FILE_HEADER) {
      throw new InputFormatError("ZIP central directory entry has an invalid signature.");
    }

    const compressionMethod = buffer.readUInt16LE(offset + 10);
    const compressedSize = buffer.readUInt32LE(offset + 20);
    const uncompressedSize = buffer.readUInt32LE(offset + 24);
    const fileNameLength = buffer.readUInt16LE(offset + 28);
    const extraLength = buffer.readUInt16LE(offset + 30);
    const commentLength = buffer.readUInt16LE(offset + 32);
    const localHeaderOffset = buffer.readUInt32LE(offset + 42);
    const nameStart = offset + 46;
    assertRange(buffer, nameStart, fileNameLength + extraLength + commentLength, "ZIP entry data is truncated.");
    const name = buffer.toString("utf8", nameStart, nameStart + fileNameLength).replaceAll("\\", "/");

    entries.set(name, {
      name,
      compressionMethod,
      compressedSize,
      uncompressedSize,
      localHeaderOffset
    });
    offset = nameStart + fileNameLength + extraLength + commentLength;
  }
  return entries;
}

export function readZipEntry(buffer, entryName) {
  const entries = readZipEntries(buffer);
  const entry = entries.get(entryName);
  if (!entry) return null;
  const localOffset = entry.localHeaderOffset;
  assertRange(buffer, localOffset, 30, "ZIP local file header is truncated.");
  if (buffer.readUInt32LE(localOffset) !== LOCAL_FILE_HEADER) {
    throw new InputFormatError("ZIP local file header has an invalid signature.");
  }
  const flags = buffer.readUInt16LE(localOffset + 6);
  if ((flags & 0x1) !== 0) {
    throw new InputFormatError("Encrypted resource-pack ZIP entries are not supported.");
  }
  const localMethod = buffer.readUInt16LE(localOffset + 8);
  if (localMethod !== entry.compressionMethod) {
    throw new InputFormatError("ZIP compression method does not match its central directory entry.");
  }
  const fileNameLength = buffer.readUInt16LE(localOffset + 26);
  const extraLength = buffer.readUInt16LE(localOffset + 28);
  const dataStart = localOffset + 30 + fileNameLength + extraLength;
  assertRange(buffer, dataStart, entry.compressedSize, "ZIP entry payload is truncated.");
  const compressed = buffer.subarray(dataStart, dataStart + entry.compressedSize);

  let output;
  if (entry.compressionMethod === 0) output = Buffer.from(compressed);
  else if (entry.compressionMethod === 8) output = inflateRawSync(compressed);
  else throw new InputFormatError(`Unsupported resource-pack ZIP compression method: ${entry.compressionMethod}`);

  if (output.length !== entry.uncompressedSize) {
    throw new InputFormatError("ZIP entry decompressed size does not match its directory record.");
  }
  return output;
}
