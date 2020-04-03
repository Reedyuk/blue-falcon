node("master") {

    stage('Checkout') {
        git url: "git@github.com:Reedyuk/blue-falcon.git", branch: env.BRANCH_NAME
    }

    stage("Build") {
        sh "gradle build"
    }

    stage("Publish") {
        sh "gradle publish"
    }
}