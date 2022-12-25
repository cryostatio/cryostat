# Unit Testing

Refer to this document for information on how to unit test Cryostat Web.

## LIBRARIES

* [Jest](https://jestjs.io/) is a Javascript testing framework used to create, run and structure unit tests. Jest also provides built-in mocking capabilities.

* [React Testing Library (RTL)](https://testing-library.com/docs/react-testing-library/intro/) is used to test the React components comprising Cryostat Web. It gives you the ability to render components into their [HTML Document Object Model (DOM)](https://developer.mozilla.org/en-US/docs/Web/API/Document_Object_Model/Introduction) representation (i.e. what the user “sees” when they visit a webpage) and [query](https://testing-library.com/docs/queries/about/)/assert on the nodes and objects in the DOM. For example, a node could be a `<button />` element that we query for and perform assertions or actions (such as a “click”) on (i.e. what the user “does” when they interact with the Cryostat Web UI).

* [Test Renderer](https://reactjs.org/docs/test-renderer.html) is used to render components into their React virtual DOM representation, a lightweight abstraction of the actual HTML DOM, consisting of pure Javascript. The render result is used to perform [snapshot testing](https://jestjs.io/docs/snapshot-testing). 

## CONFIGURATION

* `jest.config.js` contains various configuration [options](https://jestjs.io/docs/configuration) for Jest.

* `test-setup.js` allows you to set up the testing framework before any tests are run. This file is designated by the `setupFilesAfterEnv` flag in `jest.config.js`. 

* `package.json` contains the `test` and `test:ci` scripts which run the Jest test suite with different CLI options for local and Github CI testing, respectively.

## UNIT TESTING

### Overview

Use Jest's [`describe`](https://jestjs.io/docs/api#describename-fn) function to group related unit tests into a single block. The tests themselves are denoted using the [`test`](https://jestjs.io/docs/api#testname-fn-timeout) or its alias `it`. Jest also provides an extensive list of ["matchers"](https://jestjs.io/docs/expect) for making assertions. These Jest utilities do not need to be imported.

In order to render the component under test into its HTML DOM representation and perform queries on this representation, use RTL's `render` function in conjunction with `screen`, both of which can be imported from `@testing-library/react`. After the `render` call, the `screen` object can be [`queried`](https://testing-library.com/docs/queries/about) for DOM nodes/elements, which in turn can be asserted on using the aforementioned Jest matchers. There is typically one `render` call per unit test. 

### Tips

* If you insert `screen.debug()` after the `render` call for the component under test and then run the test suite, the HTML DOM representation of the component will be output to the CLI. 

* The `toBeInTheDocument` matcher is convenient for when you want to simply assert on the presence of an element in the HTML DOM. However, it is not offered by Jest but instead imported from `@testing-library/jest-dom`.

* The `within` function from `@testing-library/react` can be used to perform queries within nested elements in the HTML DOM.

* Import [`userEvent`](https://testing-library.com/docs/ecosystem-user-event) from RTL's companion library `@testing-library/user-event` in order to simulate user actions such as clicking a button. 

## MOCKING

### Overview

Refer to the Jest documentation for various mocking techniques, including [mock functions](https://jestjs.io/docs/mock-functions) and more advanced strategies such as [manual mocks](https://jestjs.io/docs/manual-mocks).

The decision to mock out a component during testing should adhere to RTL's guiding principle that [“the more your tests resemble the way your software is used, the more confidence they can give you”](https://testing-library.com/docs/guiding-principles/). Therefore, when unit testing a component make an effort to only mock out API calls, child components that belong to Cryostat Web (since they’ll have their own unit tests), and the shared services that are propagated throughout the app using the `ServiceContext`. Any third-party child components, such as those belonging to Patternfly, should be left unmocked if possible. 

### Tips

* [`jest.mock`](https://jestjs.io/docs/jest-object#jestmockmodulename-factory-options) implementations need to be defined outside the `describe` block housing the unit tests in the test file. 

* Make sure to import the component under test last. In Jest, any `jest.mock` calls are automatically hoisted to the top of the file, above the imports. This ensures that when modules are imported, Jest knows to replace the real implementations with the mocked versions. However, the actual mock implementation code isn’t processed until the component under test is imported, which is why it’s important to do this import last so that any imported modules used inside the implementations will not end up undefined.

* If you want to use mocked variables defined outside the scope of the `jest.mock` definition, you will need to import your components under test after the `jest.mock` call to prevent Jest from invoking the `jest.mock` calls before your variable is defined. For example, if you are mocking the `Api.Service` with `jest.mock` for your component under test called `MyComponent`, you will need to move `import { MyComponent } from '@app/Path/To/MyComponent';` as well as `import { ServiceContext, defaultServices } from '@app/Shared/Services/Services';` after all of your `jest.mock` calls.

* Use [`jest.requireActual`](https://jestjs.io/docs/jest-object#jestrequireactualmodulename) when you need the actual implementation of a mocked module. It can also be used to partially mock modules, allowing you to pick and choose which functions you want to mock or leave untouched. 

* Unlike `jest.mock`, [`jest.doMock`](https://jestjs.io/docs/jest-object#jestdomockmodulename-factory-options) calls are not hoisted to the top of files. This is useful for when you want to mock a module differently across tests in the same file. 

* Even though it is possible to test props directly by interacting with the mock instances receiving them, props should instead be indirectly tested by querying the rendered HTML DOM. Remember, from the user perspective all they see is this render result while having no knowledge of the underlying props used.

## SNAPSHOT TESTING

### Overview

Snapshot testing helps ensure that we stay on top of any changes to our UI. It’s a complement to regular unit testing, in which we render React components, take a serialized snapshot of the result, and compare it to a reference snapshot file to see if anything has changed. Snapshot files are committed to version control alongside their corresponding tests and are included in the review process.

When the Jest test suite runs, a new snapshot will be created for every component under test and compared to the reference snapshot in version control. If there is any discrepancy between the two snapshots a diff will be output to the command line. From here, it is up to you to determine whether the difference is due to a bug or an intentional implementation change. This may warrant updating or adding more unit tests. When you are satisfied with the reasons behind the changed snapshot, you can update it to be the new reference snapshot by running the following command:

```
npm run test -- -u -t=”SPEC_NAME” 
```

Where the `-u` flag tells Jest to update the snapshot and the `-t` flag specifies which test to update it for. `SPEC_NAME` is matched against the string passed into the `describe` call of the test file in question. For example, in `Recordings.test.tsx` the unit tests are housed inside of the `describe(‘<Recordings />’, ….)` block so in order to update the snapshot for the `Recordings` component, you would pass `-t=”<Recordings />”` to the above command. 

### Tips

* Use the `create` function from the `react-test-renderer` library to render components into their React virtual DOM representation for snapshot testing. See [here](https://javascript.plainenglish.io/react-the-virtual-dom-comprehensive-guide-acd19c5e327a) for a more detailed discussion on the virtual DOM. 

* If the component you would like to snapshot test uses `React.useEffect`, you may need to use the asynchronous `act` function from the `react-test-renderer` library to ensure the snapshot of the component is accurate. `React.useEffect` calls are run only after the render of a component is committed or "painted" to the screen. However, the nature of the virtual DOM is such that nothing is painted to the screen. Fortunately, the `act` function ensures that any state updates and enqueued effects will be executed alongside the render. 

* Some PatternFly components use random, dynamic strings as `ids` which will then be displayed as elements in the rendered React virtual DOM. These strings change upon every render, causing snapshots to fail even though the component under test is still functionally the same. This can be remedied by supplying [custom `ids` as props](https://github.com/patternfly/patternfly-react/issues/3518) to the culprit PatternFly child components inside the source file of the component under test. 
