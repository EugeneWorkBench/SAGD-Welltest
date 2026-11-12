#!/usr/bin/env ruby
# Generates SAGDWellTestApp.xcodeproj: a thin iOS app target that depends on
# the local SAGDWellTest Swift package and launches its SAGDWellTestRootView.
require "xcodeproj"

app_dir   = File.dirname(File.expand_path(__FILE__))   # .../ios/App
ios_dir   = File.dirname(app_dir)                      # .../ios
proj_path = File.join(app_dir, "SAGDWellTestApp.xcodeproj")

# Fresh project each run.
require "fileutils"
FileUtils.rm_rf(proj_path)

project = Xcodeproj::Project.new(proj_path)

# --- Local Swift package reference (the package lives one level up) ---
pkg_ref = project.new(Xcodeproj::Project::Object::XCLocalSwiftPackageReference)
pkg_ref.relative_path = ".."
project.root_object.package_references << pkg_ref

# --- App target ---
target = project.new_target(:application, "SAGDWellTestApp", :ios, "16.0")

# Product dependency on the SAGDWellTest library from the local package.
dep = project.new(Xcodeproj::Project::Object::XCSwiftPackageProductDependency)
dep.product_name = "SAGDWellTest"
dep.package = pkg_ref
target.package_product_dependencies << dep

# Link the product into the app.
link_phase = target.frameworks_build_phase
build_file = project.new(Xcodeproj::Project::Object::PBXBuildFile)
build_file.product_ref = dep
link_phase.files << build_file

# --- Source files ---
group = project.main_group.new_group("SAGDWellTestApp", "SAGDWellTestApp")
app_swift = group.new_reference(File.join(app_dir, "SAGDWellTestApp", "App.swift"))
target.source_build_phase.add_file_reference(app_swift)

# Resources bundled into the app: asset catalog (app icon) and privacy manifest.
assets = group.new_reference(File.join(app_dir, "SAGDWellTestApp", "Assets.xcassets"))
privacy = group.new_reference(File.join(app_dir, "SAGDWellTestApp", "PrivacyInfo.xcprivacy"))
target.resources_build_phase.add_file_reference(assets)
target.resources_build_phase.add_file_reference(privacy)
# Info.plist is referenced via INFOPLIST_FILE (not copied as a resource).
group.new_reference(File.join(app_dir, "SAGDWellTestApp", "Info.plist"))

# Build configuration file: backend URL/token and signing team come from here.
config_group = project.main_group.new_group("Config", "Config")
shared_xcconfig = config_group.new_reference(File.join(app_dir, "Config", "Shared.xcconfig"))

# --- Build settings (Info.plist generated, usage strings for camera/location) ---
target.build_configurations.each do |config|
  config.base_configuration_reference = shared_xcconfig
  bs = config.build_settings
  bs["INFOPLIST_FILE"]                   = "SAGDWellTestApp/Info.plist"
  bs["ASSETCATALOG_COMPILER_APPICON_NAME"] = "AppIcon"
  bs["MARKETING_VERSION"]                = "0.1.0"
  bs["CURRENT_PROJECT_VERSION"]          = "1"
  bs["PRODUCT_BUNDLE_IDENTIFIER"]        = "com.eugenekartey.sagdwelltest"
  bs["PRODUCT_NAME"]                     = "SAGD WellTest"
  bs["GENERATE_INFOPLIST_FILE"]          = "YES"
  bs["IPHONEOS_DEPLOYMENT_TARGET"]       = "16.0"
  bs["TARGETED_DEVICE_FAMILY"]           = "1,2"
  bs["SWIFT_VERSION"]                    = "5.0"
  # Automatic signing for real devices (pick your Team in Xcode). The simulator
  # needs no signing, so it is disabled only for the simulator SDK.
  bs["CODE_SIGN_STYLE"]                  = "Automatic"
  bs["CODE_SIGNING_ALLOWED"]             = "YES"
  bs["CODE_SIGNING_ALLOWED[sdk=iphonesimulator*]"] = "NO"
  bs["INFOPLIST_KEY_UILaunchScreen_Generation"] = "YES"
  bs["INFOPLIST_KEY_NSCameraUsageDescription"]   = "Scan well QR codes and capture escalation photos."
  bs["INFOPLIST_KEY_NSLocationWhenInUseUsageDescription"] = "Geotag escalation captures with your location."
  bs["INFOPLIST_KEY_UISupportedInterfaceOrientations"] =
    ["UIInterfaceOrientationPortrait", "UIInterfaceOrientationLandscapeLeft", "UIInterfaceOrientationLandscapeRight"]
end

project.save
puts "Generated #{proj_path}"
