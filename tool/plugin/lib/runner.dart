// Copyright 2017 The Chromium Authors. All rights reserved. Use of this source
// code is governed by a BSD-style license that can be found in the LICENSE file.

// @dart = 2.10

import 'dart:async';
import 'dart:io';

import 'package:args/command_runner.dart';
import 'package:path/path.dart' as p;

import 'build_spec.dart';
import 'globals.dart';
import 'util.dart';

class BuildCommandRunner extends CommandRunner {
  BuildCommandRunner()
      : super('plugin',
            'A script to build, test, and deploy the Flutter IntelliJ plugin.') {
    argParser.addOption(
      'release',
      abbr: 'r',
      help: 'The release identifier; the numeric suffix of the git branch name',
      valueHelp: 'id',
    );
    argParser.addOption(
      'cwd',
      abbr: 'd',
      help: 'For testing only; the prefix used to locate the root path (../..)',
      valueHelp: 'relative-path',
    );
  }

  void writeJxBrowserKeyToFile() {
    final jxBrowserKey =
        readTokenFromKeystore('FLUTTER_KEYSTORE_JXBROWSER_KEY_NAME');
    final propertiesFile =
        File(p.join(rootPath, "resources", "jxbrowser", "jxbrowser.properties"));
    if (jxBrowserKey.isNotEmpty) {
      final contents = '''
jxbrowser.license.key=$jxBrowserKey
''';
      propertiesFile.writeAsStringSync(contents);
    }
  }

  Future<int> buildPlugin(BuildSpec spec, String version) async {
    writeJxBrowserKeyToFile();
    return await runGradleCommand(['buildPlugin'], spec, version, 'false');
  }

  Future<int> runGradleCommand(
    List<String> command,
    BuildSpec spec,
    String version,
    String testing,
  ) async {
    var javaVersion = ['4.1'].contains(spec.version) ? '1.8' : '11';
    final contents = '''
ideaPluginName = flutter-intellij
org.gradle.parallel=true
org.gradle.jvmargs=-Xms128m -Xmx1024m -XX:+CMSClassUnloadingEnabled
javaVersion=$javaVersion
dartVersion=${spec.dartPluginVersion}
flutterPluginVersion=$version
ideaVersion=${spec.ideaVersion}
ide=${spec.ideaProduct}
testing=$testing
buildSpec=${spec.version}
baseVersion=${spec.baseVersion}
''';

    final propertiesFile = File(p.join(rootPath, "gradle.properties"));
    final source = propertiesFile.readAsStringSync();
    propertiesFile.writeAsStringSync(contents);

    // Using the Gradle daemon causes a strange problem.
    // --daemon => Invalid byte 1 of 1-byte UTF-8 sequence, which is nonsense.
    // During instrumentation of FlutterProjectStep.form, which is a UTF-8 file.
    try {
      if (spec.version == '4.1') {
        if (Platform.isWindows) {
          log('CANNOT BUILD ${spec.version} ON WINDOWS');
          return 0;
        } else {
          return await runShellScript(command, spec);
        }
      }
      return await execLocalGradleCommand(command);
    } finally {
      propertiesFile.writeAsStringSync(source);
    }
  }

  ///  *nix systems only. Executes the provided command using 'bash'
  Future<int> runShellScript(List<String> command, BuildSpec spec) async {
    var script = '''
#!/bin/bash
export JAVA_HOME=\$JAVA_HOME_OLD
./third_party/gradlew ${command.join(' ')}
''';
    var systemTempDir = Directory.systemTemp;
    var dir = systemTempDir.createTempSync();
    var file = File(p.join(dir.path, "script"));
    file.createSync();
    file.writeAsStringSync(script);
    try {
      return await exec('bash', [(file.absolute.path)]);
    } finally {
      dir.deleteSync(recursive: true);
    }
  }
}
