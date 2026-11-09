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
        keys: ['notification_channels_help', 'monitoring_battery_optimization_help'],
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
          'status_bar_icon_availability_help',
          'pref_icon_content_help',
          'pref_show_icon_unit_help',
        ],
      },
      {
        title: 'Live Updates',
        keys: [
          'status_bar_chip_availability_help',
          'settings_live_update_help',
          'pref_live_update_display_help',
          'pref_expanded_live_update_help',
        ],
        capability: 'liveUpdates',
      },
      { title: 'Permissions and notification channels', keys: ['notification_channels_help'] },
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
          'diagnostics_help_overview',
          'diagnostics_heartbeat_help',
          'diagnostics_service_help',
          'pref_autostart_at_boot_help',
        ],
      },
      {
        title: 'Background restrictions',
        keys: [
          'monitoring_battery_optimization_help',
          'diagnostics_vendor_settings_help',
          'diagnostics_dont_kill_my_app_help',
        ],
      },
      {
        title: 'Preparing a report',
        keys: ['diagnostics_help_report', 'diagnostics_debug_logs_help'],
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
          'current_state_settings_help',
          'pref_show_remaining_charge_help',
          'battery_power_explanation',
        ],
        readmeHeading: 'Current State and Time Estimates',
      },
      {
        title: 'Current units and averaging',
        keys: ['pref_battery_current_multiplier_help', 'pref_prefer_current_avg_help'],
      },
      {
        title: 'Health status and capacity estimates',
        keys: ['current_health_explanation', 'diag_help_capacity', 'diag_help_counters'],
      },
      {
        title: 'Optional device readings',
        keys: [
          'pref_use_privileged_access_help',
          'monitoring_privileged_access_help',
          'diag_help_troubleshooting',
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
        keys: ['time_estimates_settings_help', 'current_prediction_explanation'],
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
      { title: 'Learning and history', keys: ['diagnostics_predictor_help'] },
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
        keys: ['auto_log_export_help', 'csv_logs_import_help', 'csv_logs_import_warning'],
        capability: 'history',
      },
      {
        title: 'General backup and restore',
        keys: [
          'backup_restore_help_overview',
          'pref_export_general_backup_help',
          'pref_import_general_backup_help',
        ],
        readmeHeading: 'Backup and Restore',
        capability: 'backups',
      },
      {
        title: 'Separate settings and alarm backups',
        keys: [
          'pref_export_settings_help',
          'pref_import_settings_help',
          'pref_export_alarms_help',
          'pref_import_alarms_help',
        ],
        capability: 'backups',
      },
      {
        title: 'Device-specific data',
        keys: [
          'pref_export_device_data_help',
          'pref_import_device_data_help',
          'general_backup_device_data_warning',
        ],
        capability: 'backups',
      },
    ],
  },
];
