<?php

// This is a reference map for the .env values consumed by config.php.
// Keep credentials and private keys outside the repository.
return [
    'db' => [
        'host' => 'localhost',
        'name' => 'recorderdb',
        'user' => 'recorderadmin',
        'pass' => '<set-in-deployment-secret>',
    ],
    'google_service_account_json_path' => '/path/to/service-account.json',
    'android_package_name' => 'com.convert2video',
    'play_product_id' => 'pro_lifetime_unlock',
    'huawei_product_id' => 'pro_lifetime_unlock',
    'huawei_app_id' => '<set-in-deployment-secret>',
    'huawei_app_secret' => '<set-in-deployment-secret>',
    'huawei_oauth_token_url' => 'https://oauth-login.cloud.huawei.com/oauth2/v3/token',
    'huawei_order_verify_url' => 'https://orders-dre.iap.cloud.huawei.eu/applications/purchases/tokens/verify',
    'entitlement_jwt_private_key_path' => '/path/to/entitlement-private.pem',
    'pubsub_push_service_account_email' => 'pubsub-rtdn@my-project.iam.gserviceaccount.com',
    'pubsub_push_audience' => 'https://api.example.com/webhooks/rtdn',
];
