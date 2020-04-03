node("master") {

    stage("Build") {
        sh "gradle build"
    }

    stage("Publish") {
        sh "gradle publish"
    }
}