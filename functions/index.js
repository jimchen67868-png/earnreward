// AdMob server-side verification (SSV) callback.
// AdMob calls this URL after a user finishes a rewarded ad. We verify Google's
// signature, dedupe by transaction_id, enforce the daily limit, then credit points.
const { onRequest } = require("firebase-functions/v2/https");
const admin = require("firebase-admin");
const crypto = require("crypto");

admin.initializeApp();
const db = admin.firestore();

const POINTS_PER_AD = 10;   // what you pay out per ad. Keep this BELOW your revenue per ad.
const DAILY_LIMIT = 20;     // keep in sync with the app

let keyCache = { keys: null, fetchedAt: 0 };
async function getKeys() {
  if (keyCache.keys && Date.now() - keyCache.fetchedAt < 3600_000) return keyCache.keys;
  const res = await fetch("https://www.gstatic.com/admob/reward/verifier-keys.json");
  const json = await res.json();
  keyCache = { keys: json.keys, fetchedAt: Date.now() };
  return keyCache.keys;
}

function verifySignature(rawQuery, keys) {
  const sigIdx = rawQuery.indexOf("&signature=");
  if (sigIdx === -1) return false;
  const signedContent = rawQuery.substring(0, sigIdx);
  const params = new URLSearchParams(rawQuery);
  const sig = params.get("signature");
  const keyId = Number(params.get("key_id"));
  const key = keys.find((k) => k.keyId === keyId);
  if (!key || !sig) return false;
  const sigBuf = Buffer.from(sig.replace(/-/g, "+").replace(/_/g, "/"), "base64");
  return crypto.verify("sha256", Buffer.from(signedContent), key.pem, sigBuf);
}

exports.admobSsv = onRequest(async (req, res) => {
  try {
    const rawQuery = req.url.split("?")[1] || "";
    const keys = await getKeys();
    if (!verifySignature(rawQuery, keys)) return res.status(403).send("bad signature");

    const q = new URLSearchParams(rawQuery);
    const uid = q.get("user_id");
    const txId = q.get("transaction_id");
    if (!uid || !txId) return res.status(400).send("missing params");

    const today = new Date().toISOString().slice(0, 10);
    const userRef = db.collection("users").doc(uid);
    const txRef = db.collection("ssvTransactions").doc(txId);

    await db.runTransaction(async (t) => {
      if ((await t.get(txRef)).exists) return; // replay: already credited
      const snap = await t.get(userRef);
      const d = snap.exists ? snap.data() : {};
      const adsToday = d.adsDate === today ? d.adsToday || 0 : 0;
      t.set(txRef, { uid, at: admin.firestore.FieldValue.serverTimestamp() });
      if (adsToday >= DAILY_LIMIT) return;       // over limit: no credit
      t.set(userRef, {
        points: (d.points || 0) + POINTS_PER_AD,
        adsToday: adsToday + 1,
        adsDate: today,
      }, { merge: true });
    });
    res.status(200).send("ok");
  } catch (e) {
    console.error(e);
    res.status(500).send("error");
  }
});
