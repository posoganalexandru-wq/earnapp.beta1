const express = require('express');
const crypto = require('crypto');
const fs = require('fs');

const PORT = process.env.PORT || 3000;
const DB_FILE = process.env.DB_FILE || './db.json';
const REWARD_MICRO = Math.round(parseFloat(process.env.REWARD_EUR || '0.002') * 1e6); // 1 micro = 0,000001 €
const MIN_MICRO = 500000; // 0,50 €
const DAILY_LIMIT = parseInt(process.env.DAILY_REWARD_LIMIT || '30', 10);
const PAYPAL_BASE = process.env.PAYPAL_ENV === 'live'
  ? 'https://api-m.paypal.com'
  : 'https://api-m.sandbox.paypal.com';

// ---- "Baza de date" simplă într-un fișier JSON (suficientă pentru început; migrează la Postgres când crești) ----
let db = { users: {}, tokens: {}, tx: {} };
if (fs.existsSync(DB_FILE)) db = JSON.parse(fs.readFileSync(DB_FILE, 'utf8'));
const save = () => fs.writeFileSync(DB_FILE, JSON.stringify(db));
const today = () => new Date().toISOString().slice(0, 10);

const app = express();
app.use(express.json());
app.set('trust proxy', 1);

// ---- Anti-abuz: maxim 5 conturi noi pe zi per IP ----
const registerCount = {};
app.post('/register', (req, res) => {
  const key = req.ip + today();
  registerCount[key] = (registerCount[key] || 0) + 1;
  if (registerCount[key] > 5) return res.status(429).json({ error: 'Prea multe conturi create de pe acest IP' });
  const userId = crypto.randomUUID();
  const token = crypto.randomBytes(32).toString('hex');
  db.users[userId] = { token, balanceMicro: 0, history: [], pending: false };
  db.tokens[token] = userId;
  save();
  res.json({ userId, token });
});

function auth(req, res, next) {
  const token = (req.headers.authorization || '').replace('Bearer ', '');
  const id = db.tokens[token];
  if (!id) return res.status(401).json({ error: 'Neautorizat' });
  req.userId = id;
  req.user = db.users[id];
  next();
}

app.get('/me', auth, (req, res) => {
  const u = req.user;
  res.json({
    balance: u.balanceMicro / 1e6,
    history: u.history.slice(0, 50).map(h => ({
      date: h.date, label: h.label, amount: h.amountMicro / 1e6,
    })),
  });
});

// ---- Verificare server-side AdMob (SSV): Google apelează acest URL după ce utilizatorul a văzut reclama ----
let keyCache = { at: 0, keys: {} };
async function getKey(id) {
  if (!keyCache.keys[id] || Date.now() - keyCache.at > 86400000) {
    const r = await fetch('https://www.gstatic.com/admob/reward/verifier-keys.json');
    const j = await r.json();
    keyCache = { at: Date.now(), keys: Object.fromEntries(j.keys.map(k => [String(k.keyId), k.pem])) };
  }
  return keyCache.keys[String(id)];
}

app.get('/ssv', async (req, res) => {
  try {
    const qs = req.originalUrl.split('?')[1] || '';
    const i = qs.indexOf('&signature=');
    if (i < 0) return res.sendStatus(400);
    const message = qs.substring(0, i);
    const p = new URLSearchParams(qs);
    const pem = await getKey(p.get('key_id'));
    if (!pem) return res.sendStatus(400);
    const ok = crypto.verify('sha256', Buffer.from(message), pem, Buffer.from(p.get('signature'), 'base64url'));
    if (!ok) return res.sendStatus(403);

    const user = db.users[p.get('user_id')];
    const tx = p.get('transaction_id');
    if (!user || !tx) return res.sendStatus(400);
    if (db.tx[tx]) return res.sendStatus(200); // deja creditat

    const earnedToday = user.history.filter(h => h.type === 'reward' && h.date.startsWith(today())).length;
    db.tx[tx] = 1;
    if (earnedToday < DAILY_LIMIT) {
      user.balanceMicro += REWARD_MICRO;
      user.history.unshift({ type: 'reward', date: new Date().toISOString(), label: 'Reclamă vizionată', amountMicro: REWARD_MICRO });
    }
    save();
    res.sendStatus(200);
  } catch (e) {
    console.error(e);
    res.sendStatus(500);
  }
});

// ---- Retragere prin PayPal Payouts ----
async function paypalPayout(email, value, itemId) {
  const auth = Buffer.from(`${process.env.PAYPAL_CLIENT_ID}:${process.env.PAYPAL_SECRET}`).toString('base64');
  const t = await fetch(`${PAYPAL_BASE}/v1/oauth2/token`, {
    method: 'POST',
    headers: { Authorization: `Basic ${auth}`, 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'grant_type=client_credentials',
  });
  if (!t.ok) throw new Error('Autentificare PayPal eșuată');
  const { access_token } = await t.json();

  const r = await fetch(`${PAYPAL_BASE}/v1/payments/payouts`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${access_token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      sender_batch_header: {
        sender_batch_id: itemId,
        email_subject: 'Câștigurile tale din EarnApp',
      },
      items: [{
        recipient_type: 'EMAIL',
        receiver: email,
        sender_item_id: itemId,
        amount: { value, currency: 'EUR' },
      }],
    }),
  });
  if (!r.ok) throw new Error('Plata PayPal a fost refuzată');
}

app.post('/withdraw', auth, async (req, res) => {
  const u = req.user;
  const email = String(req.body.paypalEmail || '').trim();
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) return res.status(400).json({ error: 'Email PayPal invalid' });
  if (u.pending) return res.status(409).json({ error: 'O retragere este deja în curs' });
  if (u.balanceMicro < MIN_MICRO) return res.status(400).json({ error: 'Retragerea minimă este 0,50 €' });

  const cents = Math.floor(u.balanceMicro / 10000);
  const micro = cents * 10000;
  u.balanceMicro -= micro;
  u.pending = true;
  save();
  try {
    await paypalPayout(email, (cents / 100).toFixed(2), `${req.userId}-${Date.now()}`);
    u.history.unshift({ type: 'withdraw', date: new Date().toISOString(), label: 'Retragere PayPal', amountMicro: -micro });
    res.json({ amount: cents / 100 });
  } catch (e) {
    u.balanceMicro += micro; // returnăm banii în sold dacă plata a eșuat
    res.status(502).json({ error: e.message });
  } finally {
    u.pending = false;
    save();
  }
});

app.listen(PORT, () => console.log(`Backend pornit pe portul ${PORT}`));
