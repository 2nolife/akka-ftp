'use strict';

var controllers = angular.module('akkaFtp.controllers', []);

controllers.controller('dashboardCtrl', function($scope, $timeout, dashboardService) {
  var promise;

  $scope.getDashboard = function() {
    dashboardService.get(
      function(data) {
        $scope.sessionCount = data.sessionCount;
        trafficToUnits(data.traffic);
        promise = $timeout($scope.getDashboard, 2*1000);
      },
      function() {
        console.error('disconneced');
      }
    )
  };
  $scope.getDashboard();

  $scope.$on('$destroy', function(){
    $timeout.cancel(promise);
  });

  function trafficToUnits(traffic) {
    $scope.uploadSpeed = bytesToSize(traffic.uploadByteSec)+"/s";
    $scope.downloadSpeed = bytesToSize(traffic.downloadByteSec)+"/s";
    $scope.uploadTotal = bytesToSize(traffic.uploadedBytes);
    $scope.downloadTotal = bytesToSize(traffic.downloadedBytes);
  }

});

controllers.controller('sessionsCtrl', function($scope, $timeout, sessionService) {
  var promise;

  $scope.getSessions = function() {
    sessionService.sessions.get(
        function (data) {
          $scope.sessions = data.sessions;
          trafficToUnits(data.sessions)
          promise = $timeout($scope.getSessions, 10*1000);
        },
        function () {
          console.error('disconneced');
        }
    )
  };
  $scope.getSessions();

  $scope.$on('$destroy', function(){
    $timeout.cancel(promise);
  });

  function trafficToUnits(sessions) {
    sessions.forEach(function(x) {
      x.uploadTotal = bytesToSize(x.uploadedBytes);
      x.downloadTotal = bytesToSize(x.downloadedBytes);
    });
  }

});

controllers.controller('disconnectedCtrl', function($scope, $timeout, sessionService) {
  var promise;

  $scope.getSessions = function() {
    sessionService.disconnected.get(
        function (data) {
          $scope.sessions = data.sessions;
          trafficToUnits(data.sessions)
          promise = $timeout($scope.getSessions, 10*1000);
        },
        function () {
          console.error('disconneced');
        }
    )
  };
  $scope.getSessions();

  $scope.$on('$destroy', function(){
    $timeout.cancel(promise);
  });

  function trafficToUnits(sessions) {
    sessions.forEach(function(x) {
      x.uploadTotal = bytesToSize(x.uploadedBytes);
      x.downloadTotal = bytesToSize(x.downloadedBytes);
    });
  }

});

controllers.controller('controlCtrl', function($scope, controlService) {
  var doAction = function(action) {
    controlService.action(action).get(
        function (data) {
          $scope.message = data.message;
        },
        function () {
          console.error('disconneced');
        }
    )
  };

  $scope.serverShutdown = function() {
    doAction("shutdown");
  };

  $scope.serverStop = function() {
    doAction("stop");
  };

  $scope.serverSuspend = function() {
    doAction("suspend");
  };

  $scope.serverResume = function() {
    doAction("resume");
  };

  doAction("status");

});

controllers.controller('mainNavCtrl', function($scope, $location) {
  $scope.activeNav = 'control';

  $scope.pathStartsWith = function (path) {
    return $location.path().substr(0, path.length) === path ? 'active' : '';
  }
  $scope.pathEquals = function (path) {
    return $location.path() === path ? 'active' : '';
  }

});

controllers.controller('sessionCtrl', function($scope, $timeout, $routeParams, sessionService) {
  var promise;

  $scope.sessionId = $routeParams.id;

  $scope.getSession = function() {
    sessionService.session($scope.sessionId).get(
      function (data) {
        $scope.payload = data;
        promise = $timeout($scope.getSession, 2*1000);
      },
      function () {
        console.error('disconneced');
      }
    )
  };
  $scope.getSession();

  $scope.$on('$destroy', function(){
    $timeout.cancel(promise);
  });

});
