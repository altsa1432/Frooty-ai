import { GoogleGenAI } from "@google/genai";
import { getApps, initializeApp } from "firebase-admin/app";
import { createHash } from "node:crypto";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { defineSecret } from "firebase-functions/params";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { createVideoHandlers } from "./src/video-handlers.js";

const geminiApiKey = defineSecret("GEMINI_API_KEY");
const region = "us-central1";

if (getApps().length === 0) initializeApp();

function handlers() {
  return createVideoHandlers({
    getClient: () => new GoogleGenAI({ apiKey: geminiApiKey.value() }),
    getBucket: () => getStorage().bucket(),
    getOperationStore: () => ({
      save: async (operationName, uid) => {
        const id = createHash("sha256").update(operationName).digest("hex");
        await getFirestore().collection("frootyVideoOperations").doc(id).set({
          uid,
          createdAt: new Date(),
        });
      },
      owner: async (operationName) => {
        const id = createHash("sha256").update(operationName).digest("hex");
        const record = await getFirestore().collection("frootyVideoOperations").doc(id).get();
        return record.exists ? record.get("uid") : null;
      },
    }),
  });
}

function callableError(error) {
  if (error?.code === "unauthenticated" || error?.code === "permission-denied") {
    return new HttpsError(error.code, error.message);
  }
  if (error?.code === "invalid-argument") {
    return new HttpsError("invalid-argument", error.message);
  }
  if (error instanceof TypeError || error instanceof RangeError) {
    return new HttpsError("invalid-argument", error.message);
  }
  if (error instanceof HttpsError) return error;
  console.error("Veo request failed", error);
  return new HttpsError("internal", "Video generation failed. Please try again.");
}

const callableOptions = {
  region,
  enforceAppCheck: true,
  secrets: [geminiApiKey],
  timeoutSeconds: 120,
  memory: "1GiB",
};

export const startVideoGeneration = onCall(callableOptions, async (request) => {
  try {
    return await handlers().start(request.data?.prompt, request.auth?.uid);
  } catch (error) {
    throw callableError(error);
  }
});

export const getVideoGenerationStatus = onCall(callableOptions, async (request) => {
  try {
    return await handlers().status(request.data?.operationName, request.auth?.uid);
  } catch (error) {
    throw callableError(error);
  }
});
