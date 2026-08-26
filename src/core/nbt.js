import { readFile } from "node:fs/promises";
import { gunzipSync, inflateSync } from "node:zlib";
import { InputFormatError } from "./errors.js";

const Tag = Object.freeze({
  END: 0,
  BYTE: 1,
  SHORT: 2,
  INT: 3,
  LONG: 4,
  FLOAT: 5,
  DOUBLE: 6,
  BYTE_ARRAY: 7,
  STRING: 8,
  LIST: 9,
  COMPOUND: 10,
  INT_ARRAY: 11,
  LONG_ARRAY: 12
});

const MAX_DEPTH = 64;
const MAX_COLLECTION_LENGTH = 4_000_000;

function portableLong(value) {
  const asNumber = Number(value);
  return Number.isSafeInteger(asNumber) ? asNumber : value.toString();
}

class NbtReader {
  constructor(buffer) {
    this.buffer = buffer;
    this.offset = 0;
  }

  ensure(length) {
    if (!Number.isInteger(length) || length < 0 || this.offset + length > this.buffer.length) {
      throw new InputFormatError("Unexpected end of NBT data.");
    }
  }

  readByte() {
    this.ensure(1);
    return this.buffer.readInt8(this.offset++);
  }

  readUnsignedByte() {
    this.ensure(1);
    return this.buffer[this.offset++];
  }

  readShort() {
    this.ensure(2);
    const value = this.buffer.readInt16BE(this.offset);
    this.offset += 2;
    return value;
  }

  readUnsignedShort() {
    this.ensure(2);
    const value = this.buffer.readUInt16BE(this.offset);
    this.offset += 2;
    return value;
  }

  readInt() {
    this.ensure(4);
    const value = this.buffer.readInt32BE(this.offset);
    this.offset += 4;
    return value;
  }

  readLong() {
    this.ensure(8);
    const value = this.buffer.readBigInt64BE(this.offset);
    this.offset += 8;
    return portableLong(value);
  }

  readFloat() {
    this.ensure(4);
    const value = this.buffer.readFloatBE(this.offset);
    this.offset += 4;
    return value;
  }

  readDouble() {
    this.ensure(8);
    const value = this.buffer.readDoubleBE(this.offset);
    this.offset += 8;
    return value;
  }

  readString() {
    const length = this.readUnsignedShort();
    this.ensure(length);
    const value = this.buffer.toString("utf8", this.offset, this.offset + length);
    this.offset += length;
    return value;
  }

  readCollectionLength(label) {
    const length = this.readInt();
    if (length < 0 || length > MAX_COLLECTION_LENGTH) {
      throw new InputFormatError(`${label} has an unsupported length.`);
    }
    return length;
  }

  skip(length) {
    this.ensure(length);
    this.offset += length;
  }

  skipPayload(type, depth = 0) {
    if (depth > MAX_DEPTH) {
      throw new InputFormatError("NBT nesting depth is too large.");
    }
    switch (type) {
      case Tag.BYTE:
        return this.skip(1);
      case Tag.SHORT:
        return this.skip(2);
      case Tag.INT:
      case Tag.FLOAT:
        return this.skip(4);
      case Tag.LONG:
      case Tag.DOUBLE:
        return this.skip(8);
      case Tag.BYTE_ARRAY: {
        const length = this.readCollectionLength("Byte array");
        return this.skip(length);
      }
      case Tag.STRING:
        return this.skip(this.readUnsignedShort());
      case Tag.LIST: {
        const childType = this.readUnsignedByte();
        const length = this.readCollectionLength("List");
        for (let index = 0; index < length; index += 1) {
          this.skipPayload(childType, depth + 1);
        }
        return;
      }
      case Tag.COMPOUND: {
        while (true) {
          const childType = this.readUnsignedByte();
          if (childType === Tag.END) return;
          this.readString();
          this.skipPayload(childType, depth + 1);
        }
      }
      case Tag.INT_ARRAY: {
        const length = this.readCollectionLength("Int array");
        return this.skip(length * 4);
      }
      case Tag.LONG_ARRAY: {
        const length = this.readCollectionLength("Long array");
        return this.skip(length * 8);
      }
      default:
        throw new InputFormatError(`Unsupported NBT tag type: ${type}`);
    }
  }

  readPayload(type, depth = 0) {
    if (depth > MAX_DEPTH) {
      throw new InputFormatError("NBT nesting depth is too large.");
    }
    switch (type) {
      case Tag.BYTE:
        return this.readByte();
      case Tag.SHORT:
        return this.readShort();
      case Tag.INT:
        return this.readInt();
      case Tag.LONG:
        return this.readLong();
      case Tag.FLOAT:
        return this.readFloat();
      case Tag.DOUBLE:
        return this.readDouble();
      case Tag.STRING:
        return this.readString();
      case Tag.BYTE_ARRAY: {
        const length = this.readCollectionLength("Byte array");
        this.ensure(length);
        const output = this.buffer.subarray(this.offset, this.offset + length);
        this.offset += length;
        return output;
      }
      case Tag.LIST: {
        const childType = this.readUnsignedByte();
        const length = this.readCollectionLength("List");
        const output = [];
        for (let index = 0; index < length; index += 1) {
          output.push(this.readPayload(childType, depth + 1));
        }
        return output;
      }
      case Tag.COMPOUND:
        return this.readCompound(depth + 1);
      case Tag.INT_ARRAY: {
        const length = this.readCollectionLength("Int array");
        const output = [];
        for (let index = 0; index < length; index += 1) output.push(this.readInt());
        return output;
      }
      case Tag.LONG_ARRAY: {
        const length = this.readCollectionLength("Long array");
        const output = [];
        for (let index = 0; index < length; index += 1) output.push(this.readLong());
        return output;
      }
      default:
        throw new InputFormatError(`Unsupported NBT tag type: ${type}`);
    }
  }

  readCompound(depth = 0) {
    const output = {};
    while (true) {
      const type = this.readUnsignedByte();
      if (type === Tag.END) return output;
      const name = this.readString();
      output[name] = this.readPayload(type, depth + 1);
    }
  }
}

function unwrapNbt(buffer) {
  if (buffer.length >= 2 && buffer[0] === 0x1f && buffer[1] === 0x8b) {
    try {
      return gunzipSync(buffer);
    } catch {
      throw new InputFormatError("The Litematic file has an invalid gzip stream.");
    }
  }
  const zlibHeader = buffer.length >= 2 && (buffer[0] & 0x0f) === 8 && ((buffer[0] << 8) + buffer[1]) % 31 === 0;
  if (zlibHeader) {
    try {
      return inflateSync(buffer);
    } catch {
      throw new InputFormatError("The Litematic file has an invalid zlib stream.");
    }
  }
  return buffer;
}

export function readLitematicMetadataFromBuffer(input) {
  const reader = new NbtReader(unwrapNbt(input));
  const rootType = reader.readUnsignedByte();
  if (rootType !== Tag.COMPOUND) {
    throw new InputFormatError("A Litematic file must start with an NBT compound root.");
  }
  const rootName = reader.readString();
  const root = {};

  while (true) {
    const type = reader.readUnsignedByte();
    if (type === Tag.END) break;
    const name = reader.readString();

    if (name === "Metadata" || name === "Version" || name === "MinecraftDataVersion") {
      root[name] = reader.readPayload(type);
    } else {
      reader.skipPayload(type);
    }
  }

  if (reader.offset !== reader.buffer.length) {
    throw new InputFormatError("Unexpected trailing data after Litematic root compound.");
  }

  return {
    format: "litematic",
    rootName,
    formatVersion: root.Version ?? null,
    minecraftDataVersion: root.MinecraftDataVersion ?? null,
    metadata: root.Metadata ?? {}
  };
}

export async function readLitematicMetadata(filePath) {
  return readLitematicMetadataFromBuffer(await readFile(filePath));
}

export { Tag };
