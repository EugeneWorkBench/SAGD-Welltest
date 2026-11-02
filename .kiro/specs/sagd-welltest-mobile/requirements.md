# Requirements Document

## Introduction

SAGD WellTest Mobile is an offline-first field capture application for Steam-Assisted Gravity Drainage (SAGD) oil operations. Field operators travel to physical well pads in regions with unreliable cellular coverage (Athabasca, Cold Lake) to perform well tests, structured wellhead logs, visual inspections, and subcool calculations. The application stores all captured data in an on-device database and synchronizes to the cloud when connectivity becomes available. It supports structured data entry tailored to production and injection wells, an in-app subcool estimator with flashing-zone alerts, QR-based well identification, and geotagged photo escalations for maintenance issues.

This document defines the requirements for a production-ready version of the application.

## Glossary

- **App**: The SAGD WellTest Mobile application running on the operator's mobile device.
- **Local_Store**: The on-device database (SQLite or equivalent) that persists all captured data while the device is offline.
- **Sync_Service**: The component of the App responsible for transferring locally stored data to and from the cloud backend.
- **Cloud_Backend**: The remote server and data store that receives synchronized field data.
- **Operator**: A field worker who performs well tests, inspections, and logging at well pads.
- **Well**: A physical wellbore identified by a unique well identifier, classified as either a Production Well or an Injection Well.
- **Production_Well**: A Well from which emulsion (oil/water) and gas are produced.
- **Injection_Well**: A Well into which steam is injected.
- **Well_Test**: A recorded measurement session capturing oil, water, and gas ratios for a specific Well using a test separator.
- **Wellhead_Log**: A structured record of operating parameters captured at a Well.
- **Subcool_Estimator**: The in-app calculator that derives a subcool value from casing pressure and pump temperature.
- **Subcool_Value**: The temperature difference, in degrees Celsius, between the steam saturation temperature at the measured casing pressure and the measured pump temperature.
- **Flashing_Zone**: The range of Subcool_Value at or below a configured low threshold indicating a risk of steam flashing.
- **Steam_Table**: The reference dataset mapping saturation pressure to saturation temperature used by the Subcool_Estimator.
- **QR_Code**: A machine-readable code physically affixed to a wellhead or pad manifold that encodes a Well identifier.
- **Scanner**: The App component that reads QR codes or barcodes using the device camera.
- **Maintenance_Task**: A record of maintenance work associated with a Well.
- **Escalation**: A prioritized flag created by an Operator reporting a physical defect, containing a photo, GPS coordinates, and a timestamp.
- **GPS_Coordinates**: The latitude and longitude captured from the device location service.

## Requirements

### Requirement 1: Offline Local Data Persistence

**User Story:** As an Operator, I want all captured data stored on my device, so that I can continue working in areas without cellular coverage.

#### Acceptance Criteria

1. WHEN an Operator saves a Well_Test, Wellhead_Log, or Escalation, THE App SHALL persist the record to the Local_Store within 2 seconds and confirm the save by displaying a success indicator to the Operator.
2. THE App SHALL allow an Operator to create, view, and edit Well_Test, Wellhead_Log, and Escalation records while the device has no network connectivity, with all such operations completing without any requirement for network access.
3. WHEN the App is restarted, THE App SHALL load all previously persisted records from the Local_Store and make every record that was successfully persisted before restart available for viewing and editing.
4. IF a save operation to the Local_Store fails, THEN THE App SHALL retain the Operator's entered data unchanged in the active entry form, display an error message identifying which record failed to save, and preserve the prior persisted state of the record without partial modification.
5. IF available device storage is insufficient to persist a record, THEN THE App SHALL reject the save operation, retain the Operator's entered data in the active entry form, and display an error message indicating that storage is full.

### Requirement 2: Automatic Cloud Synchronization

**User Story:** As an Operator, I want my locally stored data to upload automatically when connectivity returns, so that the maintenance team receives field data without manual intervention.

#### Acceptance Criteria

1. WHILE the device has network connectivity and unsynchronized Local_Store records exist, THE Sync_Service SHALL push all unsynchronized Local_Store records to the Cloud_Backend in order of record creation time, oldest first.
2. WHEN network connectivity is restored after a period without connectivity, THE Sync_Service SHALL begin synchronizing unsynchronized records within 60 seconds.
3. WHEN a record is successfully acknowledged by the Cloud_Backend, THE App SHALL mark that record as synchronized in the Local_Store within 5 seconds of receiving the acknowledgment.
4. IF a synchronization attempt for a record fails, THEN THE Sync_Service SHALL retain the record in the Local_Store without data loss and SHALL retry synchronization on the next connectivity event.
5. IF a record's synchronization has failed on 5 consecutive attempts, THEN THE Sync_Service SHALL set that record's synchronization state to failed and SHALL stop automatic retries for that record until the Operator triggers a manual retry.
6. WHILE a record has been created locally and is not yet acknowledged and has not reached the failed state, THE App SHALL display that record's synchronization state as pending.
7. WHEN a record's synchronization state changes to pending, synchronized, or failed, THE App SHALL update the displayed synchronization state for that record within 5 seconds.
8. WHILE the device has no network connectivity, THE App SHALL display an offline indicator within 5 seconds of connectivity loss.

### Requirement 3: Production Well Wellhead Logs

**User Story:** As an Operator, I want structured input fields for production wells, so that I can record SAGD production parameters accurately.

#### Acceptance Criteria

1. WHERE the selected Well is a Production_Well, THE App SHALL present input fields for emulsion flow rate, casing gas pressure, ESP motor temperature, and water cut sample percentage.
2. WHEN an Operator enters a water cut sample percentage that is less than 0 or greater than 100, THE App SHALL reject the entry, retain the previously accepted value for that field, and display a validation message indicating the accepted range is 0 to 100.
3. WHEN an Operator enters a value containing any character other than digits, a single decimal point, or a leading minus sign in emulsion flow rate, casing gas pressure, or ESP motor temperature, THE App SHALL reject the entry, retain the previously accepted value for that field, and display a validation message indicating a numeric value is required.
4. WHEN an Operator enters an emulsion flow rate, casing gas pressure, ESP motor temperature, or water cut sample percentage outside the range 0.00 to 999,999.99, THE App SHALL reject the entry, retain the previously accepted value for that field, and display a validation message indicating the accepted range.
5. IF an Operator attempts to save a Production_Well Wellhead_Log while any of emulsion flow rate, casing gas pressure, ESP motor temperature, or water cut sample percentage is empty or contains a rejected value, THEN THE App SHALL block the save, retain all entered values, and display a validation message identifying each field requiring correction.
6. WHEN an Operator saves a Production_Well Wellhead_Log and all input fields contain accepted values, THE App SHALL associate the log with the selected Well identifier and the capture timestamp recorded to the second in UTC.

### Requirement 4: Injection Well Wellhead Logs

**User Story:** As an Operator, I want structured input fields for injection wells, so that I can record steam injection parameters accurately.

#### Acceptance Criteria

1. WHERE the selected Well is an Injection_Well, THE App SHALL present input fields for steam injection pressure, steam temperature, and steam flow rate.
2. WHEN an Operator enters a non-numeric value in steam injection pressure, steam temperature, or steam flow rate, THE App SHALL reject the entry and display a validation message indicating that only numeric input is accepted.
3. IF an Operator enters a steam injection pressure outside the range 0 to 25,000 kPa, a steam temperature outside the range 0 to 400 degrees Celsius, or a steam flow rate outside the range 0 to 2,000 cubic metres per day, THEN THE App SHALL reject the entry and display a validation message indicating the allowed range for that field.
4. IF an Operator attempts to save an Injection_Well Wellhead_Log while steam injection pressure, steam temperature, or steam flow rate is empty, THEN THE App SHALL reject the save and display a validation message identifying each missing field, and SHALL retain all entered values.
5. WHEN an Operator saves an Injection_Well Wellhead_Log with all fields valid, THE App SHALL associate the log with the selected Well identifier and the capture timestamp recorded to the second in UTC.

### Requirement 5: Subcool Estimation

**User Story:** As an Operator, I want the app to calculate subcool from casing pressure and pump temperature, so that I can identify dangerous flashing conditions in the field.

#### Acceptance Criteria

1. WHEN an Operator submits a numeric casing pressure and a numeric pump temperature to the Subcool_Estimator, THE Subcool_Estimator SHALL calculate the Subcool_Value as the difference, in degrees Celsius, between the Steam_Table saturation temperature at the submitted casing pressure and the submitted pump temperature.
2. WHEN the Subcool_Estimator completes a calculation, THE Subcool_Estimator SHALL display the calculated Subcool_Value within 1 second.
3. IF the calculated Subcool_Value is at or below the configured Flashing_Zone threshold, THEN THE App SHALL display the result in a visually distinguished flashing-zone alert state to the Operator.
4. IF a submitted casing pressure falls outside the range covered by the Steam_Table, THEN THE Subcool_Estimator SHALL reject the submission, retain the entered values, and display a message indicating the casing pressure is out of range.
5. IF an Operator submits an empty or non-numeric value for casing pressure or pump temperature, THEN THE Subcool_Estimator SHALL reject the submission, retain the entered values, and display a validation message identifying the invalid field.

### Requirement 6: QR Code Well Identification

**User Story:** As an Operator, I want to scan a wellhead QR code, so that I can immediately access that well's data without manual lookup.

#### Acceptance Criteria

1. WHEN an Operator scans a QR_Code with the Scanner, THE App SHALL decode the Well identifier encoded in the QR_Code within 2 seconds of the code entering the camera view.
2. WHEN a decoded Well identifier matches a Well in the Local_Store, THE App SHALL display that Well's historical Well_Test records ordered most recent to oldest and all open Maintenance_Task records within 3 seconds.
3. IF the decoded Well identifier does not match any Well in the Local_Store, THEN THE App SHALL display a message indicating the Well was not found and present a control to rescan.
4. IF the Scanner cannot decode a scanned code within 10 seconds, THEN THE App SHALL display a message indicating the scan was unreadable and present a control to rescan.
5. WHILE the device has no network connectivity and a matching Well exists in the Local_Store, THE App SHALL display the Well's historical data from the Local_Store with an indication that the data is from local storage.
6. IF the device has no network connectivity and no matching Well exists in the Local_Store, THEN THE App SHALL display a message indicating the Well's data is unavailable offline.

### Requirement 7: Geotagged Photo Escalations

**User Story:** As an Operator, I want to capture a geotagged photo of a defect and flag it, so that the maintenance team can prioritize repairs.

#### Acceptance Criteria

1. WHEN an Operator captures a photo for an Escalation, THE App SHALL attach the GPS_Coordinates as decimal-degree latitude and longitude and a capture timestamp recorded to the second in UTC to the Escalation.
2. IF an Operator attempts to save an Escalation without a selected priority level of High, Medium, or Low or without an associated Well identifier, THEN THE App SHALL block the save, retain the entered data, and display a validation message identifying the missing selection.
3. WHEN an Operator saves an Escalation with a photo, a priority level, and an associated Well identifier, THE App SHALL persist the Escalation and its attached photo to the Local_Store within 5 seconds.
4. IF persisting an Escalation to the Local_Store fails, THEN THE App SHALL retain the entered data, leave the Escalation in an unsaved state, and display an error message identifying the failed save.
5. IF the device location service cannot provide GPS_Coordinates within 30 seconds, THEN THE App SHALL save the Escalation with a recorded indication that location is unavailable and allow the Operator to proceed.
6. WHILE the device has network connectivity and unsynchronized Escalations exist, THE Sync_Service SHALL push each unsynchronized Escalation and its attached photo to the Cloud_Backend.
7. IF a synchronization attempt for an Escalation fails on 5 consecutive attempts, THEN THE Sync_Service SHALL set that Escalation's synchronization state to failed and stop automatic retries until the Operator triggers a manual retry.
