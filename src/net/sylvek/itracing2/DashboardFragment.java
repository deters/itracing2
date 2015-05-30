package net.sylvek.itracing2;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.view.Menu;
import android.view.MenuInflater;

/**
 * Created by sylvek on 18/05/2015.
 */
public class DashboardFragment extends PreferenceFragment {

    private OnDashboardListener presenter;

    public static DashboardFragment instance()
    {
        return new DashboardFragment();
    }

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        this.setHasOptionsMenu(true);
        this.addPreferencesFromResource(R.xml.preferences);
        findPreference(Preferences.LINK_OPTION).setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue)
            {
                if (newValue instanceof Boolean) {
                    presenter.onLinkLoss((Boolean) newValue);
                }
                return true;
            }
        });
        findPreference(Preferences.ACTION_BUTTON).setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference)
            {
                presenter.onImmediateAlert();
                return true;
            }
        });
        findPreference(Preferences.DONATE).setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference)
            {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.paypal.com/cgi-bin/webscr?cmd=_donations&business=5GMSU5NPUKCTU&lc=FR&item_name=itracing2&currency_code=EUR&bn=PP%2dDonationsBF%3abtn_donateCC_LG%2egif%3aNonHosted"));
                startActivity(browserIntent);
                return true;
            }
        });
        findPreference(Preferences.FEEDBACK).setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference)
            {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/sylvek/itracing2/issues"));
                startActivity(browserIntent);
                return true;
            }
        });
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater)
    {
        inflater.inflate(R.menu.fragment_dashboard, menu);
    }

    @Override
    public void onAttach(Activity activity)
    {
        super.onAttach(activity);
        if (activity instanceof OnDashboardListener) {
            this.presenter = (OnDashboardListener) activity;
        } else {
            throw new ClassCastException("must implement OnDashboardListener");
        }
    }

    @Override
    public void onStart()
    {
        super.onStart();
        this.presenter.onDashboardStarted();
    }

    @Override
    public void onStop()
    {
        super.onStop();
        this.presenter.onDashboardStopped();
    }

    public void setImmediateAlertEnabled(final boolean enabled)
    {
        findPreference(Preferences.ACTION_BUTTON).setEnabled(enabled);
    }

    public void setPercent(final String percent)
    {
        findPreference(Preferences.BATTERY_INFO).setSummary(percent);
    }

    public interface OnDashboardListener {

        void onImmediateAlert();

        void onLinkLoss(boolean checked);

        void onDashboardStarted();

        void onDashboardStopped();
    }
}