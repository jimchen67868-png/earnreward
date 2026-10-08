# Watch & Earn

Rewarded-ad app: users watch ads, the server (not the app) credits points after AdMob verifies the view.

## Setup
1. Create a Firebase project. Add an Android app with package `com.example.watchearn`
   (or change the package everywhere). Download `google-services.json` into `app/`.
2. Firebase console: enable **Authentication > Anonymous**, and create a **Firestore** database.
3. Deploy backend (needs Firebase CLI):
   `firebase deploy --only functions,firestore:rules`
   Upgrade the project to the Blaze plan (functions need it; usage here is tiny).
4. AdMob: create an app + a rewarded ad unit. On the ad unit, enable
   **Server-side verification** and set the callback URL to your deployed `admobSsv` function URL.
5. Replace the TEST IDs in `AndroidManifest.xml` and `MainActivity.kt` with your real ones
   (keep the test IDs while developing, since tapping real ads on your own device can get you banned).
6. Open the folder in Android Studio and run.

## Money math
Rewarded eCPMs vary widely (often a few dollars per 1000 views). Set `POINTS_PER_AD`
and your point-to-cash rate so payouts stay well under revenue.

## Before you launch
- Read Google Play's and AdMob's policies on rewarded ads and money-earning apps.
- Payouts are manual: review the `payouts` collection, pay via PayPal/gift card, then deduct
  points / mark `paid`. Watch for duplicate accounts and fraud.
- Add a privacy policy and the EU consent (UMP) flow if you have EU users.
