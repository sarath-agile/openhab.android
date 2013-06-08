/**
 * openHAB, the open Home Automation Bus.
 * Copyright (C) 2010-2012, openHAB.org <admin@openhab.org>
 *
 * See the contributors.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 * Additional permission under GNU GPL version 3 section 7
 *
 * If you modify this Program, or any covered work, by linking or
 * combining it with Eclipse (or a modified version of that library),
 * containing parts covered by the terms of the Eclipse Public License
 * (EPL), the licensors of this Program grant you additional permission
 * to convey the resulting work.
 */

package org.openhab.habdroid.ui;

import android.Manifest;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.speech.RecognizerIntent;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.*;
import android.widget.Toast;
import com.crittercism.app.Crittercism;
import com.google.analytics.tracking.android.EasyTracker;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.image.WebImageCache;

import org.apache.http.client.HttpResponseException;
import org.apache.http.entity.StringEntity;
import org.openhab.habdroid.R;
import org.openhab.habdroid.core.DocumentHttpResponseHandler;
import org.openhab.habdroid.core.OpenHABTracker;
import org.openhab.habdroid.core.OpenHABTrackerReceiver;
import org.openhab.habdroid.model.OpenHABLinkedPage;
import org.openhab.habdroid.model.OpenHABSitemap;
import org.openhab.habdroid.util.MyAsyncHttpClient;
import org.openhab.habdroid.util.Util;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

import de.duenndns.ssl.MemorizingTrustManager;


public class OpenHABMainActivity extends FragmentActivity implements OnWidgetSelectedListener, OpenHABTrackerReceiver {
    // Logging TAG
    private static final String TAG = "MainActivity";
    // Activities request codes
    private static final int VOICE_RECOGNITION_REQUEST_CODE = 1001;
    private static final int SETTINGS_REQUEST_CODE = 1002;
    private static final int WRITE_NFC_TAG_REQUEST_CODE = 1003;
    // Base URL of current openHAB connection
    private String openHABBaseUrl = "https://demo.openhab.org:8443/";
    // openHAB username
    private String openHABUsername = "";
    // openHAB password
    private String openHABPassword = "";
    // openHAB Bonjour service name
    private String openHABServiceType;
    // view pager for widgetlist fragments
    private OpenHABViewPager pager;
    // view pager adapter for widgetlist fragments
    private OpenHABFragmentPagerAdapter pagerAdapter;
    // root URL of the current sitemap
    private String sitemapRootUrl;
    // A fragment which retains it's state through configuration changes to keep the current state of the app
    private StateRetainFragment stateFragment;
    // Enable/disable development mode
    private boolean isDeveloper;
    // preferences
    private SharedPreferences mSettings;
    // OpenHAB tracker
    private OpenHABTracker mOpenHABTracker;
    // Progress dialog
    private ProgressDialog mProgressDialog;
    // If Voice Recognition is enabled
    private boolean mVoiceRecognitionEnabled = false;
    // If openHAB discovery is enabled
    private boolean mServiceDiscoveryEnabled = true;
    // Loopj
    private static MyAsyncHttpClient mAsyncHttpClient;
    //
    private boolean sitemapSelected = false;
    // NFC Launch data
    private String mNfcData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate()");
        // Check if we are in development mode
        isDeveloper = true;
        // Set default values, false means do it one time during the very first launch
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        // Set non-persistent HABDroid version preference to current version from application package
        try {
            PreferenceManager.getDefaultSharedPreferences(this).edit().putString("default_openhab_appversion",
                    getPackageManager().getPackageInfo(getPackageName(), 0).versionName).commit();
        } catch (PackageManager.NameNotFoundException e1) {
            if (e1 != null)
                Log.d(TAG, e1.getMessage());
        }
        checkDiscoveryPermissions();
        checkVoiceRecognition();
        // initialize loopj async http client
        mAsyncHttpClient = new MyAsyncHttpClient(this);
        // Set the theme to one from preferences
        Util.setActivityTheme(this);
        mSettings = PreferenceManager.getDefaultSharedPreferences(this);
        // Disable screen timeout if set in preferences
        if (mSettings.getBoolean("default_openhab_screentimeroff", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        // Fetch openHAB service type name from strings.xml
        openHABServiceType = getString(R.string.openhab_service_type);
        // Get username/password from preferences
        openHABUsername = mSettings.getString("default_openhab_username", null);
        openHABPassword = mSettings.getString("default_openhab_password", null);
        mAsyncHttpClient.setBasicAuth(openHABUsername, openHABPassword);
        mAsyncHttpClient.addHeader("Accept", "application/xml");
        mAsyncHttpClient.setTimeout(30000);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        requestWindowFeature(Window.FEATURE_PROGRESS);
        setProgressBarIndeterminateVisibility(true);
        if (!isDeveloper)
            Util.initCrittercism(getApplicationContext(), "5117659f59e1bd4ba9000004");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        pager = (OpenHABViewPager)findViewById(R.id.pager);
        pager.setScrollDurationFactor(2.5);
        pager.setOffscreenPageLimit(1);
        pagerAdapter = new OpenHABFragmentPagerAdapter(getSupportFragmentManager());
        pagerAdapter.setColumnsNumber(getResources().getInteger(R.integer.pager_columns));
        pagerAdapter.setOpenHABUsername(openHABUsername);
        pagerAdapter.setOpenHABPassword(openHABPassword);
        pager.setAdapter(pagerAdapter);
        pager.setOnPageChangeListener(pagerAdapter);
        // Check if we have openHAB page url in saved instance state?
        if (savedInstanceState != null) {
            openHABBaseUrl = savedInstanceState.getString("openHABBaseUrl");
            sitemapRootUrl = savedInstanceState.getString("sitemapRootUrl");
        }
        if (getIntent() != null) {
            Log.d(TAG, "Intent != null");
            if (getIntent().getAction() != null) {
                Log.d(TAG, "Intent action = " + getIntent().getAction());
                if (getIntent().getAction().equals("android.nfc.action.NDEF_DISCOVERED")) {
                    Log.d(TAG, "This is NFC action");
                    if (getIntent().getDataString() != null) {
                        Log.d(TAG, "NFC data = " + getIntent().getDataString());
                        mNfcData = getIntent().getDataString();
                    }
                }
            }
        }
    }

    @Override
    public void onResume() {
        Log.d(TAG, "onResume()");
        super.onResume();
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), 0);
        if (NfcAdapter.getDefaultAdapter(this) != null)
            NfcAdapter.getDefaultAdapter(this).enableForegroundDispatch(this, pendingIntent, null, null);
        if (!TextUtils.isEmpty(mNfcData)) {
            Log.d(TAG, "We have NFC data from launch");
        }
        FragmentManager fm = getSupportFragmentManager();
        stateFragment = (StateRetainFragment)fm.findFragmentByTag("stateFragment");
        if (stateFragment == null) {
            stateFragment = new StateRetainFragment();
            fm.beginTransaction().add(stateFragment, "stateFragment").commit();
            mOpenHABTracker = new OpenHABTracker(this, openHABServiceType, mServiceDiscoveryEnabled);
            mOpenHABTracker.start();
        } else {
            pagerAdapter.setFragmentList(stateFragment.getFragmentList());
        }
    }

    public void onOpenHABTracked(String baseUrl, String message) {
        if (message != null)
        Toast.makeText(getApplicationContext(), message,
                Toast.LENGTH_LONG).show();
        openHABBaseUrl = baseUrl;
        pagerAdapter.setOpenHABBaseUrl(openHABBaseUrl);
        if (!TextUtils.isEmpty(mNfcData)) {
            onNfcTag(mNfcData);
        } else {
            selectSitemap(baseUrl, false);
        }
    }

    public void onError(String error) {
        Toast.makeText(getApplicationContext(), error,
                Toast.LENGTH_LONG).show();
    }

    public void onBonjourDiscoveryStarted() {
        mProgressDialog = ProgressDialog.show(this, "",
                getString(R.string.info_discovery), true);
    }

    public void onBonjourDiscoveryFinished() {
        mProgressDialog.dismiss();
    }

    /**
     * Get sitemaps from openHAB, if user already configured preffered sitemap
     * just open it. If no preffered sitemap is configured - let user select one.
     *
     * @param  baseUrl  an absolute base URL of openHAB to open
     * @return      void
     */

    private void selectSitemap(final String baseUrl, final boolean forceSelect) {
        Log.d(TAG, "Loding sitemap list from " + baseUrl + "rest/sitemaps");
        mAsyncHttpClient.get(baseUrl + "rest/sitemaps", new DocumentHttpResponseHandler() {
            @Override
            public void onSuccess(Document document) {
                Log.d(TAG, "Response: " +  document.toString());
                List<OpenHABSitemap> sitemapList = Util.parseSitemapList(document);
                if (sitemapList.size() == 0) {
                    // Got an empty sitemap list!
                    Log.e(TAG, "openHAB returned empty sitemap list");
                    showAlertDialog(getString(R.string.error_empty_sitemap_list));
                    return;
                }
                // If we are forced to do selection, just open selection dialog
                if (forceSelect) {
                    showSitemapSelectionDialog(sitemapList);
                } else {
                    // Check if we have a sitemap configured to use
                    SharedPreferences settings =
                            PreferenceManager.getDefaultSharedPreferences(OpenHABMainActivity.this);
                    String configuredSitemap = settings.getString("default_openhab_sitemap", "");
                    // If we have sitemap configured
                    if (configuredSitemap.length() > 0) {
                        // Configured sitemap is on the list we got, open it!
                        if (Util.sitemapExists(sitemapList, configuredSitemap)) {
                            Log.d(TAG, "Configured sitemap is on the list");
                            OpenHABSitemap selectedSitemap = Util.getSitemapByName(sitemapList, configuredSitemap);
                            openSitemap(selectedSitemap.getHomepageLink());
                            // Configured sitemap is not on the list we got!
                        } else {
                            Log.d(TAG, "Configured sitemap is not on the list");
                            if (sitemapList.size() == 1) {
                                Log.d(TAG, "Got only one sitemap");
                                SharedPreferences.Editor preferencesEditor = settings.edit();
                                preferencesEditor.putString("default_openhab_sitemap", sitemapList.get(0).getName());
                                preferencesEditor.commit();
                                openSitemap(sitemapList.get(0).getHomepageLink());
                            } else {
                                Log.d(TAG, "Got multiply sitemaps, user have to select one");
                                showSitemapSelectionDialog(sitemapList);
                            }
                        }
                        // No sitemap is configured to use
                    } else {
                        // We got only one single sitemap from openHAB, use it
                        if (sitemapList.size() == 1) {
                            Log.d(TAG, "Got only one sitemap");
                            SharedPreferences.Editor preferencesEditor = settings.edit();
                            preferencesEditor.putString("default_openhab_sitemap", sitemapList.get(0).getName());
                            preferencesEditor.commit();
                            openSitemap(sitemapList.get(0).getHomepageLink());
                        } else {
                            Log.d(TAG, "Got multiply sitemaps, user have to select one");
                            showSitemapSelectionDialog(sitemapList);
                        }
                    }
                }
            }
            @Override public void onFailure(Throwable error, String content) {
                if (error instanceof HttpResponseException) {
                    switch (((HttpResponseException) error).getStatusCode()) {
                        case 401:
                            showAlertDialog(getString(R.string.error_authentication_failed));
                            break;
                        default:
                            Log.e(TAG, String.format("Http code = %d", ((HttpResponseException) error).getStatusCode()));
                            break;
                    }
                } else {
                    Log.e(TAG, error.getClass().toString());
                }
            }
        });

    }

    private void showSitemapSelectionDialog(final List<OpenHABSitemap> sitemapList) {
        Log.d(TAG, "Opening sitemap selection dialog");
        final List<String> sitemapNameList = new ArrayList<String>();;
        for (int i=0; i<sitemapList.size(); i++) {
            sitemapNameList.add(sitemapList.get(i).getName());
        }
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(OpenHABMainActivity.this);
        dialogBuilder.setTitle(getString(R.string.mainmenu_openhab_selectsitemap));
        try {
            dialogBuilder.setItems(sitemapNameList.toArray(new CharSequence[sitemapNameList.size()]),
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int item) {
                            Log.d(TAG, "Selected sitemap " + sitemapNameList.get(item));
                            SharedPreferences settings =
                                    PreferenceManager.getDefaultSharedPreferences(OpenHABMainActivity.this);
                            SharedPreferences.Editor preferencesEditor = settings.edit();
                            preferencesEditor.putString("default_openhab_sitemap", sitemapList.get(item).getName());
                            preferencesEditor.commit();
                            openSitemap(sitemapList.get(item).getHomepageLink());
                        }
                    }).show();
        } catch (WindowManager.BadTokenException e) {
            Crittercism.logHandledException(e);
        }
    }

    private void openSitemap(String sitemapUrl) {
        Log.i(TAG, "Opening sitemap at " + sitemapUrl);
        sitemapSelected = true;
        sitemapRootUrl = sitemapUrl;
        pagerAdapter.clearFragmentList();
        pagerAdapter.openPage(sitemapRootUrl);
        pager.setCurrentItem(0);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.mainmenu_voice_recognition).setVisible(mVoiceRecognitionEnabled);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.mainmenu_openhab_preferences:
                Intent settingsIntent = new Intent(this.getApplicationContext(), OpenHABPreferencesActivity.class);
                startActivityForResult(settingsIntent, SETTINGS_REQUEST_CODE);
                Util.overridePendingTransition(this, false);
                return true;
            case R.id.mainmenu_openhab_selectsitemap:
                SharedPreferences settings =
                        PreferenceManager.getDefaultSharedPreferences(OpenHABMainActivity.this);
                SharedPreferences.Editor preferencesEditor = settings.edit();
                preferencesEditor.putString("default_openhab_sitemap", "");
                preferencesEditor.commit();
                selectSitemap(openHABBaseUrl, true);
                return true;
            case android.R.id.home:
                Log.d(TAG, "Home selected - " + sitemapRootUrl);
                // TODO: rewrite 'Home' action to use OpenHABFragmentPagerAdapter.
                // Get launch intent for application
/*                Intent homeIntent = getBaseContext().getPackageManager()
                        .getLaunchIntentForPackage( getBaseContext().getPackageName() );
                homeIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                homeIntent.setAction("org.openhab.habdroid.ui.OpwnHABWidgetListActivity");
                homeIntent.putExtra("displayPageUrl", sitemapRootUrl);
                homeIntent.putExtra("openHABBaseUrl", openHABBaseUrl);
                homeIntent.putExtra("sitemapRootUrl", sitemapRootUrl);
                // Finish current activity
                finish();
                // Start launch activity
                startActivity(homeIntent);
                Util.overridePendingTransition(this, true);*/
                return true;
            case R.id.mainmenu_openhab_clearcache:
                Log.d(TAG, "Restarting");
                // Get launch intent for application
                Intent restartIntent = getBaseContext().getPackageManager()
                        .getLaunchIntentForPackage( getBaseContext().getPackageName() );
                restartIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                // Finish current activity
                finish();
                WebImageCache cache = new WebImageCache(getBaseContext());
                cache.clear();
                // Start launch activity
                startActivity(restartIntent);
                // Start launch activity
                return true;
            case R.id.mainmenu_openhab_writetag:
                Intent writeTagIntent = new Intent(this.getApplicationContext(), OpenHABWriteTagActivity.class);
                // TODO: get current display page url, which? how? :-/
                OpenHABWidgetListFragment currentFragment = pagerAdapter.getFragment(pager.getCurrentItem());
                writeTagIntent.putExtra("sitemapPage", currentFragment.getDisplayPageUrl());
                startActivityForResult(writeTagIntent, WRITE_NFC_TAG_REQUEST_CODE);
                Util.overridePendingTransition(this, false);
                return true;
            case R.id.mainmenu_voice_recognition:
                launchVoiceRecognition();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(TAG, String.format("onActivityResult requestCode = %d, resultCode = %d", requestCode, resultCode));
        switch (requestCode) {
            case SETTINGS_REQUEST_CODE:
                // Restart app after preferences
                Log.d(TAG, "Restarting after settings");
                // Get launch intent for application
                Intent restartIntent = getBaseContext().getPackageManager()
                        .getLaunchIntentForPackage( getBaseContext().getPackageName() );
                restartIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                // Finish current activity
                finish();
                // Start launch activity
                startActivity(restartIntent);
                break;
            case WRITE_NFC_TAG_REQUEST_CODE:
                Log.d(TAG, "Got back from Write NFC tag");
                break;
            case VOICE_RECOGNITION_REQUEST_CODE:
                Log.d(TAG, "Got back from Voice recognition");
                setProgressBarIndeterminateVisibility(false);
                if(resultCode == RESULT_OK) {
                    ArrayList<String> textMatchList = data
                            .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                    if (!textMatchList.isEmpty()) {
                        Log.d(TAG, textMatchList.get(0));
                        Log.d(TAG, "Recognized text: " + textMatchList.get(0));
                        Toast.makeText(this, "I recognized: " + textMatchList.get(0),
                                Toast.LENGTH_LONG).show();
                    } else {
                        Log.d(TAG, "Voice recognition returned empty set");
                        Toast.makeText(this, "I can't read you!",
                                Toast.LENGTH_LONG).show();
                    }
                } else {
                    Log.d(TAG, "A voice recognition error occured");
                    Toast.makeText(this, "A voice recognition error occured",
                            Toast.LENGTH_LONG).show();
                }
                break;
            default:
        }
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        Log.d(TAG, "onSaveInstanceState");

        // Save UI state changes to the savedInstanceState.
        // This bundle will be passed to onCreate if the process is
        // killed and restarted.
        savedInstanceState.putString("openHABBaseUrl", openHABBaseUrl);
        savedInstanceState.putString("sitemapRootUrl", sitemapRootUrl);
        savedInstanceState.putInt("currentFragment", pager.getCurrentItem());
        super.onSaveInstanceState(savedInstanceState);
    }

    /**
     * Overriding onStart to enable Google Analytics stats collection
     */
    @Override
    public void onStart() {
        super.onStart();
        // Start activity tracking via Google Analytics
        if (!isDeveloper)
            EasyTracker.getInstance().activityStart(this);
    }

    /**
     * Overriding onStop to enable Google Analytics stats collection
     */
    @Override
    public void onStop() {
        Log.d(TAG, "onStop()");
        super.onStop();
        // Stop activity tracking via Google Analytics
        if (!isDeveloper)
            EasyTracker.getInstance().activityStop(this);
        if (mOpenHABTracker != null)
            mOpenHABTracker.stop();
    }

    @Override
    public void onPause() {
        Log.d(TAG, "onPause()");
        super.onPause();
        stateFragment.setFragmentList(pagerAdapter.getFragmentList());
//        Runnable can = new Runnable() {
//            public void run() {
//                mAsyncHttpClient.cancelRequests(OpenHABMainActivity.this, true);
//            }
//        };
//        new Thread(can).start();
    }

    /**
     * This method is called when activity receives a new intent while running
     */
    @Override
    public void onNewIntent(Intent newIntent) {
        if (newIntent.getAction() != null) {
            Log.d(TAG, "New intent received = " + newIntent.getAction().toString());
            if (newIntent.getAction().equals("android.nfc.action.NDEF_DISCOVERED")) {
                Log.d(TAG, "This is NFC action");
                if (newIntent.getDataString() != null) {
                    Log.d(TAG, "Action data = " + newIntent.getDataString());
                    onNfcTag(newIntent.getDataString());
                }
            }
        }
    }

    /**
     * This method processes new intents generated by NFC subsystem
     * @param nfcData - a data which NFC subsystem got from the NFC tag
     */
    public void onNfcTag(String nfcData) {
        Log.d(TAG, "onNfcTag()");
        Uri openHABURI = Uri.parse(nfcData);
        Log.d(TAG, "NFC Scheme = " + openHABURI.getScheme());
        Log.d(TAG, "NFC Host = " + openHABURI.getHost());
        Log.d(TAG, "NFC Path = " + openHABURI.getPath());
        String nfcItem = openHABURI.getQueryParameter("item");
        String nfcCommand = openHABURI.getQueryParameter("command");
        String nfcItemType = openHABURI.getQueryParameter("itemType");
        // If there is no item parameter it means tag contains only sitemap page url
        if (TextUtils.isEmpty(nfcItem)) {
            Log.d(TAG, "This is a sitemap tag without parameters");
            // Form the new sitemap page url
            String newPageUrl = openHABBaseUrl + "rest/sitemaps" + openHABURI.getPath();
            // Check if we have this page in stack?
            int possiblePosition = pagerAdapter.getPositionByUrl(newPageUrl);
            // If yes, then just switch to this page
            if (possiblePosition >= 0) {
                pager.setCurrentItem(possiblePosition);
            // If not, then open this page as new one
            } else {
                pagerAdapter.openPage(newPageUrl);
                pager.setCurrentItem(pagerAdapter.getCount()-1);
            }
        } else {
            Log.d(TAG, "Target item = " + nfcItem);
            try {
                StringEntity se = new StringEntity(nfcCommand);
                mAsyncHttpClient.post(this, openHABBaseUrl + "rest/items/" + nfcItem, se, "text/plain", new AsyncHttpResponseHandler() {
                    @Override
                    public void onSuccess(String response) {
                        Log.d(TAG, "Command was sent successfully");
                    }
                    @Override
                    public void onFailure(Throwable error, String errorResponse) {
                        Log.e(TAG, "Got command error " + error.getMessage());
                        if (errorResponse != null)
                            Log.e(TAG, "Error response = " + errorResponse);
                    }
                });
            } catch (UnsupportedEncodingException e) {
                if (e != null)
                    Log.e(TAG, e.getMessage());
            }
            if (!TextUtils.isEmpty(mNfcData)) {
                Log.d(TAG, "This was NFC activity launch, exiting");
                finish();
            }
        }
        mNfcData = "";
    }

    public void onWidgetSelectedListener(OpenHABLinkedPage linkedPage) {
        Log.i(TAG, "Got widget link = " + linkedPage.getLink());
        pagerAdapter.openPage(linkedPage.getLink());
        pager.setCurrentItem(pagerAdapter.getCount()-1);
        setTitle(linkedPage.getTitle());
    }

    @Override
    public void onBackPressed() {
        Log.d(TAG, String.format("onBackPressed() I'm at the %d page", pager.getCurrentItem()));
        if (pager.getCurrentItem() == 0) {
            super.onBackPressed();
        } else {
            pager.setCurrentItem(pager.getCurrentItem() - 1, true);
            setTitle(pagerAdapter.getPageTitle(pager.getCurrentItem()));
        }
    }

    public void startProgressIndicator() {
        setProgressBarIndeterminateVisibility(true);
    }

    public void stopProgressIndicator() {
        setProgressBarIndeterminateVisibility(false);
    }

    private void launchVoiceRecognition() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        // Specify the calling package to identify your application
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getClass().getPackage().getName());
        // Display an hint to the user about what he should say.
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "openHAB, at your command!");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        startActivityForResult(intent, VOICE_RECOGNITION_REQUEST_CODE);
    }


    private void showAlertDialog(String alertMessage) {
        AlertDialog.Builder builder = new AlertDialog.Builder(OpenHABMainActivity.this);
        builder.setMessage(alertMessage)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                    }
                });
        AlertDialog alert = builder.create();
        alert.show();
    }

    public void checkVoiceRecognition() {
        // Check if voice recognition is present
        PackageManager pm = getPackageManager();
        List<ResolveInfo> activities = pm.queryIntentActivities(new Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0);
        if (activities.size() == 0) {
//            speakButton.setEnabled(false);
//            speakButton.setText("Voice recognizer not present");
            Toast.makeText(this, "Voice recognizer not present, voice recognition disabled",
                    Toast.LENGTH_SHORT).show();
        } else {
            mVoiceRecognitionEnabled = true;
        }
    }

    public void checkDiscoveryPermissions() {
        // Check if we got all needed permissions
        PackageManager pm = getPackageManager();
        if (!(pm.checkPermission(Manifest.permission.CHANGE_WIFI_MULTICAST_STATE, getPackageName()) == PackageManager.PERMISSION_GRANTED)) {
            showAlertDialog(getString(R.string.erorr_no_wifi_mcast_permission));
            mServiceDiscoveryEnabled = false;
        }
        if (!(pm.checkPermission(Manifest.permission.ACCESS_WIFI_STATE, getPackageName()) == PackageManager.PERMISSION_GRANTED)) {
            showAlertDialog(getString(R.string.erorr_no_wifi_state_permission));
            mServiceDiscoveryEnabled = false;
        }

    }

    public String getOpenHABBaseUrl() {
        return openHABBaseUrl;
    }

    public void setOpenHABBaseUrl(String openHABBaseUrl) {
        this.openHABBaseUrl = openHABBaseUrl;
    }

    public String getOpenHABUsername() {
        return openHABUsername;
    }

    public void setOpenHABUsername(String openHABUsername) {
        this.openHABUsername = openHABUsername;
    }

    public String getOpenHABPassword() {
        return openHABPassword;
    }

    public void setOpenHABPassword(String openHABPassword) {
        this.openHABPassword = openHABPassword;
    }

    public static MyAsyncHttpClient getAsyncHttpClient() {
        return mAsyncHttpClient;
    }

}