// Mock out the services shared across the app in order to help isolate 
// components from the ServiceContext

jest.mock('@app/Shared/Services/Api.service');
jest.mock('@app/Shared/Services/Login.service');
jest.mock('@app/Shared/Services/NotificationChannel.service');
jest.mock('@app/Shared/Services/Report.service');
jest.mock('@app/Shared/Services/Settings.service');
jest.mock('@app/Shared/Services/Target.service');
jest.mock('@app/Shared/Services/Targets.service');
