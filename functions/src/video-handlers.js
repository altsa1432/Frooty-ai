import { createHash, randomUUID } from "node:crypto";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { readFile, unlink } from "node:fs/promises";

const MODEL = "veo-3.1-generate-preview";
const MAX_PROMPT_LENGTH = 1500;

export function validateVideoPrompt(prompt) {
  if (typeof prompt !== "string") {
    throw new TypeError("Enter a text prompt for the video.");
  }
  const cleaned = prompt.trim();
  if (cleaned.length < 10 || cleaned.length > MAX_PROMPT_LENGTH) {
    throw new RangeError(`Prompt must be 10–${MAX_PROMPT_LENGTH} characters.`);
  }
  return cleaned;
}

export function validateOperationName(name) {
  if (
    typeof name !== "string" ||
    name.length > 256 ||
    !/^(?:models\/[A-Za-z0-9._-]+\/)?operations\/[A-Za-z0-9_-]+$/.test(name)
  ) {
    throw new TypeError("Invalid video operation.");
  }
  return name;
}

export function createVideoHandlers({ getClient, getBucket, getOperationStore }) {
  return {
    async start(promptValue, uid) {
      if (typeof uid !== "string" || uid.length === 0) {
        const error = new Error("Authentication is required.");
        error.code = "unauthenticated";
        throw error;
      }
      const prompt = validateVideoPrompt(promptValue);
      const operation = await getClient().models.generateVideos({
        model: MODEL,
        prompt,
      });
      if (typeof operation.name !== "string") {
        throw new Error("The video provider did not return an operation.");
      }
      validateOperationName(operation.name);
      await getOperationStore().save(operation.name, uid);
      return { operationName: operation.name };
    },

    async status(operationNameValue, uid) {
      if (typeof uid !== "string" || uid.length === 0) {
        const error = new Error("Authentication is required.");
        error.code = "unauthenticated";
        throw error;
      }
      const operationName = validateOperationName(operationNameValue);
      const ownerUid = await getOperationStore().owner(operationName);
      if (!ownerUid || ownerUid !== uid) {
        const error = new Error("This video operation does not belong to this account.");
        error.code = "permission-denied";
        throw error;
      }
      const client = getClient();
      const operation = await client.operations.getVideosOperation({
        operation: { name: operationName },
      });
      if (!operation.done) return { status: "processing" };
      if (operation.error) throw new Error("Video generation failed.");

      const video = operation.response?.generatedVideos?.[0]?.video;
      if (!video) throw new Error("The video provider returned no video.");

      const objectName = `veo-results/${createHash("sha256")
        .update(operationName)
        .digest("hex")}.mp4`;
      const object = getBucket().file(objectName);
      const [exists] = await object.exists();
      if (!exists) {
        const localPath = join(tmpdir(), `${randomUUID()}.mp4`);
        try {
          await client.files.download({ file: video, downloadPath: localPath });
              await object.save(await readFile(localPath), {
            resumable: false,
            metadata: { contentType: "video/mp4" },
          });
        } finally {
          await unlink(localPath).catch((error) => {
            if (error.code !== "ENOENT") throw error;
          });
        }
      }

      const [videoUrl] = await object.getSignedUrl({
        action: "read",
        expires: Date.now() + 15 * 60 * 1000,
        version: "v4",
      });
      return { status: "complete", videoUrl };
    },
  };
}
