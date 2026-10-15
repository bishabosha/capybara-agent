import platform,runpy,sys
# This stand-in does not load Apple code and can run on any test platform.
platform.system=lambda:'Darwin'
runpy.run_path(sys.argv[1],run_name='__main__')
