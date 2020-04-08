node("master") {

    stage('Checkout') {
        git url: "git@github.com:Reedyuk/blue-falcon.git", branch: env.BRANCH_NAME
    }

    stage("Build") {
        sh "./gradlew library:build"
    }

    stage("Publish") {
        sh "./gradlew library:publish"
    }
}