import assert from "node:assert/strict";
import { writeFile } from "node:fs/promises";
import test from "node:test";
import {
  createVideoHandlers,
  validateOperationName,
  validateVideoPrompt,
} from "../src/video-handlers.js";

test("video prompt is trimmed and length limited", () => {
  assert.equal(validateVideoPrompt("  A cinematic forest scene  "), "A cinematic forest scene");
  assert.throws(() => validateVideoPrompt("short"), RangeError);
  assert.throws(() => validateVideoPrompt("x".repeat(1501)), RangeError);
  assert.throws(() => validateVideoPrompt(null), TypeError);
});

test("operation names are limited to provider operation IDs", () => {
  assert.equal(validateOperationName("operations/abc_123"), "operations/abc_123");
  assert.equal(
    validateOperationName("models/veo-3.1-generate-preview/operations/abc_123"),
    "models/veo-3.1-generate-preview/operations/abc_123",
  );
  assert.throws(() => validateOperationName("../../private"), TypeError);
});

test("start creates a Veo 3.1 video operation", async () => {
  let request;
  const handlers = createVideoHandlers({
    getClient: () => ({
      models: {
        generateVideos: async (value) => {
          request = value;
          return { name: "operations/abc123" };
        },
      },
    }),
    getBucket: () => assert.fail("Storage is not needed when starting generation"),
    getOperationStore: () => ({
      save: async (name, uid) => {
        assert.equal(name, "operations/abc123");
        assert.equal(uid, "user-1");
      },
    }),
  });

  assert.deepEqual(await handlers.start("A cinematic forest scene", "user-1"), {
    operationName: "operations/abc123",
  });
  assert.equal(request.model, "veo-3.1-generate-preview");
});

test("status reports processing before a video is ready", async () => {
  const handlers = createVideoHandlers({
    getClient: () => ({
      operations: { getVideosOperation: async () => ({ done: false }) },
    }),
    getBucket: () => assert.fail("Storage is not needed while processing"),
    getOperationStore: () => ({ owner: async () => "user-1" }),
  });
  assert.deepEqual(await handlers.status("operations/abc123", "user-1"), { status: "processing" });
});

test("completed generation downloads once and returns a signed MP4 URL", async () => {
  let downloads = 0;
  let savedBytes;
  let signedOptions;
  let exists = false;
  const handlers = createVideoHandlers({
    getClient: () => ({
      operations: {
        getVideosOperation: async () => ({
          done: true,
          response: { generatedVideos: [{ video: { name: "files/generated-video" } }] },
        }),
      },
      files: {
        download: async ({ downloadPath }) => {
          downloads += 1;
          await writeFile(downloadPath, Buffer.from("video bytes"));
        },
      },
    }),
    getBucket: () => ({
      file: (path) => ({
        exists: async () => [exists],
        save: async (bytes, options) => {
          exists = true;
          savedBytes = Buffer.from(bytes);
          assert.equal(options.metadata.contentType, "video/mp4");
        },
        getSignedUrl: async (options) => {
          signedOptions = options;
          return ["https://storage.example/frooty.mp4?sig=test"];
        },
        name: path,
      }),
    }),
    getOperationStore: () => ({ owner: async () => "user-1" }),
  });

  const result = await handlers.status("operations/abc123", "user-1");
  assert.equal(result.status, "complete");
  assert.equal(result.videoUrl, "https://storage.example/frooty.mp4?sig=test");
  assert.equal(savedBytes.toString(), "video bytes");
  assert.equal(downloads, 1);
  assert.equal(signedOptions.version, "v4");
  assert.ok(signedOptions.expires > Date.now());
});

test("users cannot poll another user's video operation", async () => {
  let providerCalled = false;
  const handlers = createVideoHandlers({
    getClient: () => {
      providerCalled = true;
      return {};
    },
    getBucket: () => assert.fail("Storage must not be accessed"),
    getOperationStore: () => ({ owner: async () => "owner-user" }),
  });
  await assert.rejects(
    handlers.status("operations/abc123", "different-user"),
    (error) => error.code === "permission-denied",
  );
  assert.equal(providerCalled, false);
});

test("video calls require an authenticated app user", async () => {
  const handlers = createVideoHandlers({
    getClient: () => assert.fail("Provider must not be called"),
    getBucket: () => assert.fail("Storage must not be accessed"),
    getOperationStore: () => assert.fail("Operation store must not be accessed"),
  });
  await assert.rejects(
    handlers.start("A cinematic forest scene", ""),
    (error) => error.code === "unauthenticated",
  );
});
