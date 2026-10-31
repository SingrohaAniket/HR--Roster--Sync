INSERT INTO tenant (id, code, name) VALUES
    (1, 'ACME',   'Acme Demo Corp'),
    (2, 'GLOBEX', 'Globex Demo Corp');
 
INSERT INTO office (id, tenant_id, code, name, city) VALUES
    (1, 1, 'BLR-WHF', 'Acme Whitefield',      'Bengaluru'),
    (2, 1, 'BLR-ECO', 'Acme Ecoworld',        'Bengaluru'),
    (3, 1, 'HYD-HTC', 'Acme Hitec City',      'Hyderabad'),
    (4, 2, 'DEL-CYB', 'Globex Cyber City',    'Gurugram'),
    (5, 2, 'PUN-HNJ', 'Globex Hinjawadi',     'Pune'),
    (6, 2, 'MUM-BKC', 'Globex Bandra Kurla',  'Mumbai');

INSERT INTO app_user (id, tenant_id, username, password_hash, role, employee_external_id) VALUES
    (1, 1, 'acme.employee',   '$2a$10$qT8QcLqM/kk8a.GABnJD4eHnFHDuZF9zwFWjx57tDcSyN/NoXyPnW', 'EMPLOYEE', 'EMP-1002'),
    (2, 1, 'acme.admin',      '$2a$10$.LlpDWEdJdLFAf43Z9uGYeGiSmU618IbBdO8k.3RxTFSYCj7Jv5Mi', 'ADMIN',    NULL),
    (3, 1, 'acme.operator',   '$2a$10$BuURRS5VFx65JMuRHx6E3.afPwXxZ1kzL0RNJt9vK11h/SQCgvoTu', 'OPERATOR', NULL),
    (4, 2, 'globex.employee', '$2a$10$XTRhuW2sV4lkppbJyfw/Ze.aOUpRI9XZgCxBEAH.gGJp04pfTDuOu', 'EMPLOYEE', 'GLX-2001'),
    (5, 2, 'globex.admin',    '$2a$10$bDhnWJ4TLr/3xm4SuUZC1e9sGehsWqXykUqc.WEwKTjB95gaYi8X.', 'ADMIN',    NULL),
    (6, 2, 'globex.operator', '$2a$10$c5DsKaV9zu0mQaFvEG2f1OyHNoXvABqdTgB1GsnP1V/w4a4lbWAD2', 'OPERATOR', NULL);

INSERT INTO employee
    (id, tenant_id, external_id, name, email, phone, manager_external_id, office_id, shift_code,
     pickup_lat, pickup_lng, pickup_label, special_assistance,
     status, hr_version, transport_version, hr_updated_at, transport_updated_at,
     content_hash, bucket, deactivated_at)
VALUES
    (1, 1, 'EMP-1001', 'Demo Employee One', 'emp1001@acme.example', '+919000001001', 'EMP-1005', 1, 'S1',
     12.970000, 77.750000, 'Demo Pickup Point A', NULL,
     'ACTIVE', 5, 1, DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 30 DAY), DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 30 DAY),
     'b6673abe149e162110eedd39d3313cbd043915615e676e4039aa23a9619ec994', 101, NULL),
    (2, 1, 'EMP-1002', 'Demo Employee Two', 'emp1002@acme.example', '+919000001002', 'EMP-1005', 1, 'S1',
     12.980000, 77.740000, 'Demo Pickup Point B', 'Demo wheelchair access',
     'ACTIVE', 5, 1, DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 30 DAY), DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 30 DAY),
     '8be560aff1680a9fda5a6b49e3dff5269f5ce2b3e8b5704f96807ceae6977029', 102, NULL),
    (3, 1, 'EMP-1003', 'Demo Employee Three', 'emp1003@acme.example', '+919000001003', 'EMP-1005', 2, 'S2',
     NULL, NULL, NULL, NULL,
     'ACTIVE', 5, 0, DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 30 DAY), NULL,
     '152cc18fa112567817cef3f44a52c02a39eb9f44ea0cc3f57cefd3cdeab070c5', 103, NULL),
    (4, 1, 'EMP-1004', 'Demo Employee Four', 'emp1004@acme.example', '+919000001004', 'EMP-1005', 3, 'S2',
     NULL, NULL, NULL, NULL,
     'ACTIVE', 5, 0, DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 30 DAY), NULL,
     '1b0eb8eecb7dd3ba482bb94d346a95a8aacbe1e260897db3ab06a163738a810f', 104, NULL),
    (5, 1, 'EMP-1005', 'Demo Manager Five', 'emp1005@acme.example', '+919000001005', NULL, 1, 'S1',
     NULL, NULL, NULL, NULL,
     'ACTIVE', 5, 0, DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 30 DAY), NULL,
     '0a306b9d76f57a802ecfa96894de614e1d124d2dbb890fe3f26dea633dc97fef', 105, NULL),
    (6, 1, 'EMP-1006', 'Demo Leaver Six', 'emp1006@acme.example', '+919000001006', 'EMP-1005', 2, 'S1',
     NULL, NULL, NULL, NULL,
     'INACTIVE', 5, 0, DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 30 DAY), NULL,
     'ccd2e4ef961ca2c8782bc07a1e59e8fdaf7cb2ae0a2a2beb78c84c8a6f1a038b', 106, DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 30 DAY)),
    (7, 2, 'GLX-2001', 'Demo Globex One', 'glx2001@globex.example', '+919000002001', NULL, 4, 'S1',
     NULL, NULL, NULL, NULL,
     'ACTIVE', 5, 0, DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 30 DAY), NULL,
     '1ecb405ea26cfe94155a4f8861858c9706b1f5986a8ff98a3eec4b9a7765e673', 57, NULL);

INSERT INTO trip (id, tenant_id, trip_date, shift_code, office_id, total_seats, seats_taken) VALUES
    (1, 1, DATE_SUB(CURRENT_DATE, INTERVAL 7 DAY), 'S1', 1, 4, 2),
    (2, 1, DATE_ADD(CURRENT_DATE, INTERVAL 1 DAY), 'S1', 1, 3, 3),
    (3, 1, DATE_ADD(CURRENT_DATE, INTERVAL 3 DAY), 'S1', 1, 4, 1),
    (4, 1, DATE_ADD(CURRENT_DATE, INTERVAL 7 DAY), 'S2', 2, 4, 1),
    (5, 1, DATE_ADD(CURRENT_DATE, INTERVAL 5 DAY), 'S1', 1, 4, 0);

INSERT INTO booking (id, tenant_id, employee_id, trip_id, trip_date, status, cancel_reason, cancelled_at) VALUES
    (1, 1, 1, 1, DATE_SUB(CURRENT_DATE, INTERVAL 7 DAY), 'BOOKED', NULL, NULL),
    (2, 1, 2, 1, DATE_SUB(CURRENT_DATE, INTERVAL 7 DAY), 'BOOKED', NULL, NULL),
    (3, 1, 1, 2, DATE_ADD(CURRENT_DATE, INTERVAL 1 DAY), 'BOOKED', NULL, NULL),
    (4, 1, 2, 2, DATE_ADD(CURRENT_DATE, INTERVAL 1 DAY), 'BOOKED', NULL, NULL),
    (5, 1, 5, 2, DATE_ADD(CURRENT_DATE, INTERVAL 1 DAY), 'BOOKED', NULL, NULL),
    (6, 1, 1, 3, DATE_ADD(CURRENT_DATE, INTERVAL 3 DAY), 'BOOKED', NULL, NULL),
    (7, 1, 3, 4, DATE_ADD(CURRENT_DATE, INTERVAL 7 DAY), 'BOOKED', NULL, NULL),
    (8, 1, 1, 5, DATE_ADD(CURRENT_DATE, INTERVAL 5 DAY), 'CANCELLED', 'DEMO_USER_CANCELLED', UTC_TIMESTAMP(3));
