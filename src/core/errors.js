export class ValidationError extends Error {
  constructor(message, details = []) {
    super(message);
    this.name = "ValidationError";
    this.details = details;
  }
}

export class InputFormatError extends Error {
  constructor(message) {
    super(message);
    this.name = "InputFormatError";
  }
}
