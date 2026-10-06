# EarnApp — aplicație Android cu reclame recompensate și retragere PayPal

Utilizatorul vede reclame, primește o parte din venit în sold și retrage pe PayPal de la 0,50 €.

## Cum câștigi tu bani
Google te plătește pe tine (AdMob) pentru fiecare reclamă. Tu dai utilizatorului o parte
(`REWARD_EUR` în `backend/.env`) și păstrezi diferența. Setează `REWARD_EUR` sub ce câștigi
efectiv per reclamă, altfel pierzi bani.

## Pornire locală (test)

### 1. Backend
```
cd backend
npm install
cp .env.example .env     # completează PAYPAL_CLIENT_ID și PAYPAL_SECRET (sandbox)
npm start
```
Necesită Node 20 sau mai nou.

### 2. Aplicația Android
1. Deschide folderul `android/` în Android Studio (generează singur Gradle wrapper).
2. Rulează pe emulator. Varianta debug se conectează la backend pe `http://10.0.2.2:3000`.
3. Reclamele sunt de test (ID-urile de test Google sunt deja puse).

## Publicare reală
1. Cont AdMob: creează aplicația și o unitate de reclamă „Rewarded”. Pune ID-urile în
   `app/build.gradle.kts` (`REWARDED_AD_UNIT`) și în `AndroidManifest.xml` (`APPLICATION_ID`).
2. În AdMob, la unitatea Rewarded, activează „Server-side verification” și pune URL-ul
   `https://serverul-tau.example.com/ssv`.
3. Pune backend-ul pe un server cu HTTPS (Railway, Render, VPS) și schimbă `BASE_URL` pe release.
4. PayPal: creează o aplicație la developer.paypal.com, cere accesul la Payouts, apoi `PAYPAL_ENV=live`.
5. Publică pe Google Play. Adaugă politică de confidențialitate (obligatorie cu reclame).

## De știut
- Taxele PayPal la plăți mici pot depăși câștigul tău; ia în calcul un minim de retragere mai mare.
- Veniturile din reclame diferă mult după țară. Măsoară eCPM-ul real înainte să fixezi `REWARD_EUR`.
- Fișierul `db.json` merge pentru început. Pentru mulți utilizatori, treci la o bază de date (Postgres).
- Anti-fraudă de bază există (limită zilnică, limită de conturi pe IP). Fraudatorii apar rapid, deci urmărește retragerile.
