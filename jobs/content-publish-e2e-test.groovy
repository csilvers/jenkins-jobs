// The pipeline job for end-to-end tests run after a content publish.
//
// Ideally we'd run this after every publish, but in reality we run it
// on a schedule (once every 10 minutes).
//
// Because it runs so frequently, we do not want to use the test
// worker machines, like we do with e2e-test.groovy.  Instead, we do
// all our work on saucelabs.  This makes the test a bit slower to
// run, but uses someone else's resources instead of ours. :-)


@Library("kautils")
// Classes we use, under jenkins-tools/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-tools/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.onMaster
//import vars.withSecrets


new Setup(steps

).blockBuilds(["builds-using-saucelabs"]

).addCronSchedule("H/10 * * * *"

).addStringParam(
    "URL",
    "The url-base to run these tests against.",
    "https://www.khanacademy.org"

).addStringParam(
    "SLACK_CHANNEL",
    "The slack channel to which to send failure alerts.",
    "#content-beep-boop"

).addStringParam(
    "PUBLISH_USERNAME",
    "Name of the user who triggered the publish action.",
    ""

).addStringParam(
    "PUBLISH_MESSAGE",
    "The message associated with the publish action.",
    ""

).addBooleanParam(
   "FORCE",
   """If set, run the tests even if the database says that the e2e tests
have already passed for this webapp version + publish commit.""",
   false

).addStringParam(
    "DEPLOYER_USERNAME",
    """Who asked to run this job, used to ping on slack. Typically not set
manually, but rather by other jobs that call this one.""",
    ""

).apply();


// This should be called from workspace-root.
def _alert(def msg, def isError=true, def channel=params.SLACK_CHANNEL) {
   withSecrets() {     // you need secrets to talk to slack
      sh("echo ${exec.shellEscape(msg)} | " +
         "jenkins-tools/alertlib/alert.py " +
         "--slack=${exec.shellEscape(channel)} " +
         "--severity=${isError ? 'error' : 'info'} " +
         "--chat-sender='Testing Turtle' --icon-emoji=:turtle:");
   }
}


// This is used to check if we've already run this exact e2e test.
// This is necessary because this job gets run on a schedule; we
// don't want to redo work if nothing has changed since the last run!
def getRedisKey() {
   onMaster('30m') {
      def versionJson = sh(
         script: ("curl -s ${exec.shellEscape(params.URL)}" +
                  "/api/internal/dev/version"),
         returnStdout: true).trim();
      def versionId = sh(
         script: ("echo ${exec.shellEscape(versionJson)} | " +
                  "python -c 'import json, sys;" +
                  " x = json.load(sys.stdin);" +
                  " v = x[\"version_id\"].split(\".\")[0];" +
                  " s = x[\"static_version_id\"];" +
                  " print v if v == s else v + \"-\" + s'"),
         returnStdout: true).trim();

      def publishCommitJson = sh(
         script: ("curl -s ${exec.shellEscape(params.URL)}" +
                  "/api/internal/misc/last_published_commit"),
         returnStdout: true).trim();
      def publishCommitId = sh(
         script: ("echo ${exec.shellEscape(publishCommitJson)} | " +
                  "python -c 'import json, sys;" +
                  " print json.load(sys.stdin)[\"last_published_commit\"]'"),
         returnStdout: true).trim();

      return "e2etest:${versionId}:${publishCommitId}";
   }
};


def syncWebapp() {
   kaGit.safeSyncTo("git@github.com:Khan/webapp", "master");
   dir("webapp") {
      sh("make python_deps");
   }
}


def runAndroidTests() {
   onMaster('1h') {       // timeout
      withEnv(["URL=${params.URL}"]) {
         withSecrets() {  // we need secrets to talk to slack!
            try {
               sh("jenkins-tools/run_android_db_generator.sh");
               _alert("Mobile integration tests succeeded",
                      isError=false);
            } catch (e) {
               def msg = ("Mobile integration tests failed " +
                          "(search for 'ANDROID' in " +
                          "${env.BUILD_URL}consoleFull)");
               _alert(msg, isError=true);
               // TODO(charlie): Re-enable sending these failures to
               // #mobile-1s-and-0s.  They're too noisy right now,
               // making the channel unusable on some days, and these
               // failures are rarely urgent (so, e.g., if we only
               // notice them the next morning when the nightly e2es
               // run, that's fine). Note that the tests are mostly
               // failing during the publish e2es.  See:
               // https://app.asana.com/0/31965416896056/268841235736013.
               //_alert(msg, isError=true, channel="#mobile-1s-and-0s");
               throw e;
            }
         }
      }
   }
}


def runEndToEndTests() {
   onMaster("1h") {
      // Out with the old, in with the new!
      sh("rm -f webapp/genfiles/test-results.pickle");

      // This is apparently needed to avoid hanging with
      // the chrome driver.  See
      // https://github.com/SeleniumHQ/docker-selenium/issues/87
      // We also work around https://bugs.launchpad.net/bugs/1033179
      withEnv(["DBUS_SESSION_BUS_ADDRESS=/dev/null",
               "TMPDIR=/tmp"]) {
         withSecrets() {   // we need secrets to talk to saucelabs
            dir("webapp") {
               exec(["tools/rune2etests.py",
                     "--pickle", "--pickle-file=genfiles/test-results.pickle",
                     // JOBS=9 leaves one sauce machine available
                     // for deploy e2e tests (which uses sauce on
                     // test failure).
                     "--quiet", "--jobs=9", "--retries=3",
                     "--url", params.URL, "--driver=sauce"])
            }
         }
      }
   }
}


// 'label' is attached to the slack message to help identify the job.
def analyzeResults(label) {
   onMaster("15m") {
      dir("webapp") {
         if (!fileExists("webapp/test-results.pickle")) {
            def msg = ("The e2e tests did not even finish " +
                       "(could be due to timeouts or framework " +
                       "errors; check ${env.BUILD_URL}consoleFull " +
                       "to see exactly why)");
            _alert(msg, isError=true);
            return;
         }

         withSecrets() {      // we need secrets to talk to slack.
            if (params.PUBLISH_MESSAGE) {
               label += ": ${params.PUBLISH_MESSAGE}";
            }
            // We prefer to say the publisher "did" the deploy, if available.
            def deployer = params.PUBLISH_USERNAME or params.DEPLOYER_USERNAME;
            exec(["tools/test_pickle_util.py", "summarize-to-slack",
                  "genfiles/test-results.pickle", params.SLACK_CHANNEL,
                  "--jenkins-build-url", env.BUILD_URL,
                  "--commit", label,
                  "--deployer", deployer]);
            // Let notify() know not to send any messages to slack,
            // because we just did it above.
            env.SENT_TO_SLACK = '1';
         }

         sh("rm -rf genfiles/test-reports");
         sh("tools/test_pickle_util.py to-junit " +
            "genfiles/test-results.pickle genfiles/test-reports");
      }
      junit("webapp/genfiles/test-reports/*.xml");
   }
}


notify([slack: [channel: params.SLACK_CHANNEL,
                sender: 'Testing Turtle',
                emoji: ':turtle:',
                when: ['FAILURE', 'UNSTABLE', 'ABORTED']]]) {
   def key = getRedisKey();

   currentBuild.displayName = "${currentBuild.displayName} (${key})";

   singleton.storeEvenOnFailure(params.FORCE ? null : key) {
      stage("Syncing webapp") {
         syncWebapp();
      }
      stage("Running android tests") {
         runAndroidTests();
      }
      try {
         stage("Running e2e tests") {
            runEndToEndTests();
         }
      } finally {
         stage("Analyzing results") {
            analyzeResults(key);
         }
      }
   }
}