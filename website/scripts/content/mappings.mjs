// Missing sections are omitted for older releases; their text never falls back to the checkout.
export const ARTICLE_MAPPINGS = [
  {
    id: 'battery-alarms',
    title: 'Battery alarms',
    intro:
      'Understand the alarm rules available in this app version, their thresholds, and the Android settings that affect delivery.',
    sections: [
      {
        title: 'Available rules and configuration',
        keys: ['alarms_overview_help', 'pref_alarm_type_help'],
        readmeHeading: 'Battery Alarms',
        capability: 'alarms',
      },
      {
        title: 'When a threshold fires',
        keys: ['pref_alarm_threshold_help'],
        capability: 'alarms',
      },
      {
        title: 'Sound, vibration and delivery',
        keys: [
          { helpKey: 'notification_channels_help', title: 'Android notification channels' },
          {
            helpKey: 'monitoring_battery_optimization_help',
            titleKey: 'diagnostics_battery_optimization',
          },
        ],
        capability: 'alarms',
      },
    ],
  },
  {
    id: 'notifications',
    title: 'Notifications and Live Updates',
    intro:
      'Find the notification options supported by this app version and understand the limits imposed by Android and your device.',
    sections: [
      {
        title: 'Main notification',
        navigation: ['settings_activity_subtitle', 'notification_settings'],
        keys: [
          'notification_settings_help',
          'pref_top_line_content_help',
          'pref_expanded_notification_help',
        ],
        readmeHeading: 'Main Notification',
      },
      {
        title: 'Status-bar appearance',
        keys: [
          { helpKey: 'status_bar_icon_availability_help', titleKey: 'status_bar_icon_settings' },
          'pref_icon_content_help',
          'pref_show_icon_unit_help',
        ],
      },
      {
        title: 'Live Updates',
        keys: [
          { helpKey: 'status_bar_chip_availability_help', titleKey: 'status_bar_chip_settings' },
          { helpKey: 'settings_live_update_help', title: 'Android Live Update permission' },
          'pref_live_update_display_help',
          'pref_expanded_live_update_help',
        ],
        capability: 'liveUpdates',
      },
      {
        title: 'Permissions and notification channels',
        keys: [{ helpKey: 'notification_channels_help', title: 'Android notification channels' }],
      },
    ],
  },
  {
    id: 'background-monitoring',
    title: 'Background monitoring',
    intro:
      'Check monitoring status and Android background restrictions when readings or alarms arrive late, then prepare useful information for a report.',
    sections: [
      {
        title: 'Monitor status',
        keys: [
          { helpKey: 'diagnostics_help_overview', titleKey: 'diagnostics' },
          { helpKey: 'diagnostics_heartbeat_help', title: 'Latest monitoring response' },
          'diagnostics_service_help',
          'pref_autostart_at_boot_help',
        ],
      },
      {
        title: 'Background restrictions',
        keys: [
          {
            helpKey: 'monitoring_battery_optimization_help',
            titleKey: 'diagnostics_battery_optimization',
          },
          'diagnostics_vendor_settings_help',
          'diagnostics_dont_kill_my_app_help',
        ],
      },
      {
        title: 'Preparing a report',
        keys: [
          { helpKey: 'diagnostics_help_report', titleKey: 'diagnostics_export' },
          'diagnostics_debug_logs_help',
        ],
      },
    ],
  },
  {
    id: 'battery-readings',
    title: 'Battery readings',
    intro:
      'Read the available measurements in context. Android and manufacturers expose different information, so a missing value does not by itself indicate a fault.',
    sections: [
      {
        title: 'Current measurements',
        keys: [
          {
            helpKey: 'current_state_settings_help',
            titleKey: 'tab_current_info',
            navigation: ['settings_activity_subtitle', 'tab_current_info'],
          },
          'pref_show_remaining_charge_help',
          { helpKey: 'battery_power_explanation', titleKey: 'battery_power' },
        ],
        readmeHeading: 'Current State and Time Estimates',
      },
      {
        title: 'Current units and averaging',
        keys: [
          'pref_battery_current_multiplier_help',
          {
            helpKey: 'pref_prefer_current_avg_help',
            titleKey: 'pref_prefer_average_battery_current',
          },
        ],
      },
      {
        title: 'Health status and capacity estimates',
        keys: [
          { helpKey: 'current_health_explanation', title: 'Android battery health status' },
          {
            helpKey: 'diag_help_capacity',
            titleKeys: ['diag_section_capacity', 'advanced_section_capacity'],
          },
          {
            helpKey: 'diag_help_counters',
            titleKeys: ['diag_section_counters', 'advanced_section_counters'],
          },
        ],
      },
      {
        title: 'Optional device readings',
        keys: [
          { helpKey: 'advanced_help_overview', titleKey: 'tab_advanced' },
          'pref_use_privileged_access_help',
          { helpKey: 'monitoring_privileged_access_help', title: 'Available additional readings' },
          {
            helpKey: 'diag_help_troubleshooting',
            titleKeys: ['diag_help_troubleshooting_title', 'advanced_help_troubleshooting_title'],
          },
        ],
        readmeHeading: 'Appearance and Advanced Information',
        capability: 'privileged',
      },
    ],
  },
  {
    id: 'charging-targets',
    title: 'Charging targets and time estimates',
    intro:
      'Understand the targets and prediction methods available in this app version, including their relationship to estimates and alarms.',
    sections: [
      {
        title: 'What a target means',
        navigation: ['settings_activity_subtitle', 'time_estimates_settings'],
        keys: [
          'time_estimates_settings_help',
          { helpKey: 'current_prediction_explanation', title: 'Remaining-time estimate' },
        ],
      },
      {
        title: 'Automatic and custom targets',
        keys: [
          'pref_charging_target_mode_help',
          'pref_custom_charging_target_help',
          'pref_discharging_target_help',
        ],
      },
      { title: 'Prediction methods', keys: ['pref_prediction_type_help'] },
      {
        title: 'Learning and history',
        keys: [{ helpKey: 'diagnostics_predictor_help', title: 'Saved averages and recent rates' }],
      },
    ],
  },
  {
    id: 'history-and-backups',
    title: 'History, exports and backups',
    intro:
      'Understand what history records, how retention and exports work, and what a restore can replace before importing saved data.',
    sections: [
      {
        title: 'Recorded observations and retention',
        keys: ['pref_enable_logging_help', 'pref_max_log_age_help'],
        readmeHeading: 'History and Logs',
        capability: 'history',
      },
      {
        title: 'History export and import',
        keys: [
          { helpKey: 'auto_log_export_help', title: 'Automatic log exports' },
          { helpKey: 'csv_logs_import_help', titleKey: 'pref_import_logs_csv' },
          { helpKey: 'csv_logs_import_warning', title: 'CSV compatibility' },
        ],
        capability: 'history',
      },
      {
        title: 'General backup and restore',
        navigation: ['settings_activity_subtitle', 'pref_backup_restore'],
        keys: [
          { helpKey: 'backup_restore_help_overview', titleKey: 'pref_backup_restore' },
          'pref_export_general_backup_help',
          'pref_import_general_backup_help',
        ],
        readmeHeading: 'Backup and Restore',
        capability: 'backups',
      },
      {
        title: 'Separate settings and alarm backups',
        keys: [
          { helpKey: 'pref_export_settings_help', legacyTitle: 'Settings backup' },
          'pref_import_settings_help',
          { helpKey: 'pref_export_alarms_help', legacyTitle: 'Alarms backup' },
          'pref_import_alarms_help',
        ],
        capability: 'backups',
      },
      {
        title: 'Device-specific data',
        keys: [
          { helpKey: 'pref_export_device_data_help', legacyTitle: 'Device-specific backup' },
          'pref_import_device_data_help',
          { helpKey: 'general_backup_device_data_warning', title: 'Restoring on another device' },
        ],
        capability: 'backups',
      },
    ],
  },
];
