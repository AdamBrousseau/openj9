timestamps {
	node('sw.os.windows') {
		sh '''
		echo $PATH
		git --version
		which git
		ls -al /usr/bin
		/usr/bin/git --version
		'''
	}
}