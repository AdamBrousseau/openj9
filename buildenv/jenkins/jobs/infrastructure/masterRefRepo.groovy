timestamps {
	sh """
		cd ${JENKINS_HOME}
		mkdir -p openjdk_cache
		cd openjdk_cache
		git init --bare
		git config remote.openj9.url https://github.com/eclipse/openj9.git
		git config remote.openj9.fetch +refs/heads/*:refs/remotes/openj9/*
		git fetch --all
	"""
}